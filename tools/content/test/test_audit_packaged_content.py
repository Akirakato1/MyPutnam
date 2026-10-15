import importlib.util
import json
import shutil
import sqlite3
import tempfile
import unittest
import warnings
import zipfile
from contextlib import closing
from pathlib import Path


ROOT = Path(__file__).parents[1]
REPOSITORY_ROOT = ROOT.parents[1]
AUDITOR_PATH = ROOT / "src" / "audit-packaged-content.py"
BUNDLE_TEST_PATH = Path(__file__).with_name("test_content_bundle_audit.py")


def load_module(name, path):
    if not path.exists():
        return None
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BUNDLE_FIXTURE = load_module("bundle_fixture", BUNDLE_TEST_PATH)
AUDITOR = load_module("audit_packaged_content", AUDITOR_PATH)


class PackagedContentAuditTest(unittest.TestCase):
    def setUp(self):
        test_temp_root = REPOSITORY_ROOT / "build/test-temp"
        test_temp_root.mkdir(parents=True, exist_ok=True)
        self.temporary_directory = tempfile.TemporaryDirectory(dir=test_temp_root)
        self.root = Path(self.temporary_directory.name)
        self.assets = self.root / "assets"
        self.release, self.catalog, self.manifest = BUNDLE_FIXTURE.create_bundle(self.assets)
        self.expected_release = self.root / "expected-release.json"
        self.expected_release.write_text(json.dumps(self.release, indent=2) + "\n", encoding="utf-8")
        self.apk = self.root / "fixture.apk"
        write_apk(self.assets, self.apk)

    def tearDown(self):
        self.temporary_directory.cleanup()

    def audit(self):
        self.assertIsNotNone(AUDITOR, "audit-packaged-content.py must exist")
        return AUDITOR.audit_packaged_apk(self.apk, self.expected_release)

    def rebuild(self, extra_members=None, duplicate_member=None):
        write_apk(self.assets, self.apk, extra_members=extra_members, duplicate_member=duplicate_member)

    def test_coherent_apk_is_audited_from_archive_bytes(self):
        report = self.audit()

        self.assertEqual("fixture-release", report["releaseId"])
        self.assertEqual(1, report["counts"]["problemCount"])
        self.assertEqual(4, report["counts"]["hintCount"])
        self.assertEqual(1, report["counts"]["prerequisiteCount"])

    def test_android_generated_assets_outside_the_content_bundle_are_ignored(self):
        self.rebuild(extra_members={"assets/dexopt/baseline.prof": b"android profile"})

        report = self.audit()

        self.assertEqual("fixture-release", report["releaseId"])

    def test_checked_in_travel_bundle_satisfies_the_exact_release_contract(self):
        production_apk = self.root / "travel-release-fixture.apk"
        production_assets = REPOSITORY_ROOT / "app/src/main/assets"
        production_release = REPOSITORY_ROOT / "content-src/releases/putnam-2020-2025-v0.4.0.json"
        write_apk(production_assets, production_apk)

        report = AUDITOR.audit_packaged_apk(production_apk, production_release)

        self.assertEqual(6, report["counts"]["setCount"])
        self.assertEqual(72, report["counts"]["problemCount"])
        self.assertEqual(288, report["counts"]["hintCount"])
        self.assertGreaterEqual(report["counts"]["prerequisiteCount"], 96)

    def test_powershell_verifier_delegates_content_checks_to_the_apk_auditor(self):
        verifier = (REPOSITORY_ROOT / "scripts/verify-release.ps1").read_text(encoding="utf-8")
        self.assertIn("audit-packaged-content.py", verifier)
        self.assertIn("--expected-release", verifier)
        self.assertIn("$LASTEXITCODE", verifier)
        self.assertIn('$ErrorActionPreference = "Continue"', verifier)
        self.assertNotIn("assets/content/2025/manifest.json", verifier)
        self.assertIn(".Replace('\\:', ':').Replace('\\\\', '\\')", verifier)

    def test_missing_database_and_stale_database_metadata_are_rejected(self):
        (self.assets / "databases/content.db").unlink()
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "missing|content.db"):
            self.audit()

        self.reset_bundle()
        with closing(sqlite3.connect(self.assets / "databases/content.db")) as database:
            database.execute("UPDATE content_meta SET value = 'stale' WHERE key = 'content_version'")
            database.commit()
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "metadata|content_version"):
            self.audit()

    def test_expected_release_mismatch_and_set_selection_drift_are_rejected(self):
        wrong = dict(self.release)
        wrong["appVersionCode"] = 99
        self.expected_release.write_text(json.dumps(wrong), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "release manifest|contract"):
            self.audit()

        wrong = dict(self.release)
        wrong["includedSets"] = ["putnam/2024", "putnam/2025"]
        self.expected_release.write_text(json.dumps(wrong), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "release manifest|selected|missing"):
            self.audit()

    def test_tampered_or_missing_asset_is_rejected(self):
        statement = self.assets / "content/putnam/2025/problems/A1/statement.html"
        statement.write_text("tampered", encoding="utf-8")
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "hash|byte length|statement"):
            self.audit()

        self.reset_bundle()
        (self.assets / "content/putnam/2025/problems/A1/solution.html").unlink()
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "missing|solution"):
            self.audit()

    def test_transitive_font_and_external_dependencies_are_rejected(self):
        css_path = "content/shared/katex/katex.min.css"
        css = self.assets / css_path
        css.write_text("@font-face{src:url(fonts/missing.woff2)}", encoding="utf-8")
        BUNDLE_FIXTURE.refresh_asset_hash(self.assets, self.manifest, css_path)
        BUNDLE_FIXTURE.write_manifest(self.assets, self.manifest)
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "Missing local dependency|missing.woff2"):
            self.audit()

        self.reset_bundle()
        statement_path = "content/putnam/2025/problems/A1/statement.html"
        statement = self.assets / statement_path
        statement.write_text('<img src="https://example.test/leak.png">', encoding="utf-8")
        BUNDLE_FIXTURE.refresh_asset_hash(self.assets, self.manifest, statement_path)
        BUNDLE_FIXTURE.write_manifest(self.assets, self.manifest)
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "External dependency|forbidden"):
            self.audit()

    def test_orphan_topic_and_actual_prerequisite_count_drift_are_rejected(self):
        with closing(sqlite3.connect(self.assets / "databases/content.db")) as database:
            database.execute("PRAGMA foreign_keys = OFF")
            database.execute("UPDATE problem_topic SET topic_id = 'orphan'")
            database.commit()
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "foreign|topic"):
            self.audit()

        self.reset_bundle()
        with closing(sqlite3.connect(self.assets / "databases/content.db")) as database:
            database.execute("UPDATE content_meta SET value = '999' WHERE key = 'prerequisite_count'")
            database.commit()
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "prerequisite_count|metadata"):
            self.audit()

    def test_database_logical_rows_must_match_the_embedded_manifests(self):
        with closing(sqlite3.connect(self.assets / "databases/content.db")) as database:
            database.execute(
                "UPDATE problem SET statement_asset_path = solution_asset_path "
                "WHERE key = 'putnam/2025/A1'"
            )
            database.execute("UPDATE hint SET title = 'stale title' WHERE hint_order = 2")
            database.commit()
        self.rebuild()

        with self.assertRaisesRegex(ValueError, "logical rows|problem|manifest"):
            self.audit()

    def test_release_cardinality_policy_rejects_wrong_72_and_288_counts(self):
        self.assertIsNotNone(AUDITOR, "audit-packaged-content.py must exist")
        release = {
            "releaseId": "putnam-2020-2025-v0.4.0",
            "includedSets": [f"putnam/{year}" for year in range(2020, 2026)],
        }
        baseline = {"collectionCount": 1, "setCount": 6, "topicCount": 13}
        with self.assertRaisesRegex(ValueError, "72"):
            AUDITOR.audit_release_cardinality(
                release,
                {**baseline, "problemCount": 71, "hintCount": 288, "prerequisiteCount": 200},
            )
        with self.assertRaisesRegex(ValueError, "288"):
            AUDITOR.audit_release_cardinality(
                release,
                {**baseline, "problemCount": 72, "hintCount": 287, "prerequisiteCount": 200},
            )
        with self.assertRaisesRegex(ValueError, "prerequisite"):
            AUDITOR.audit_release_cardinality(
                release,
                {**baseline, "problemCount": 72, "hintCount": 288, "prerequisiteCount": 71},
            )

    def test_travel_release_requires_exact_a1_through_b6_identities_and_ordinals(self):
        release = {
            "releaseId": "putnam-2020-2025-v0.4.0",
            "includedSets": [f"putnam/{year}" for year in range(2020, 2026)],
        }
        problem_ids = [f"A{number}" for number in range(1, 7)] + [f"B{number}" for number in range(1, 7)]
        manifests = [
            {
                "collectionId": "putnam",
                "setId": str(year),
                "problems": [
                    {
                        "key": f"putnam/{year}/{problem_id}",
                        "collectionId": "putnam",
                        "setId": str(year),
                        "problemId": problem_id,
                        "ordinal": ordinal,
                    }
                    for ordinal, problem_id in enumerate(problem_ids, 1)
                ],
            }
            for year in range(2020, 2026)
        ]
        AUDITOR.audit_travel_problem_identities(release, manifests)

        manifests[0]["problems"][0].update({"key": "putnam/2020/C1", "problemId": "C1"})
        with self.assertRaisesRegex(ValueError, "A1|B6|identity|ordinal"):
            AUDITOR.audit_travel_problem_identities(release, manifests)

    def test_forbidden_development_files_are_rejected(self):
        for path in (
            "assets/content/paper.pdf",
            "assets/content/source.tex",
            "assets/content/original/raw.txt",
            "assets/content/review/note.txt",
            "assets/dexopt/disguised-source.pdf",
            "res/raw/original.pdf",
            "review/notes.txt",
        ):
            with self.subTest(path=path):
                self.rebuild(extra_members={path: b"forbidden"})
                with self.assertRaisesRegex(ValueError, "Forbidden|forbidden|unlisted"):
                    self.audit()

    def test_unsafe_and_duplicate_zip_members_are_rejected_before_extraction(self):
        self.rebuild(extra_members={"../escape.txt": b"escape"})
        with self.assertRaisesRegex(ValueError, "Unsafe|traversal|path"):
            self.audit()

        self.rebuild(duplicate_member="assets/content/release.json")
        with self.assertRaisesRegex(ValueError, "Duplicate|duplicate"):
            self.audit()

    def reset_bundle(self):
        shutil.rmtree(self.assets, ignore_errors=True)
        self.release, self.catalog, self.manifest = BUNDLE_FIXTURE.create_bundle(self.assets)
        self.expected_release.write_text(json.dumps(self.release, indent=2) + "\n", encoding="utf-8")


def write_apk(asset_root, destination, extra_members=None, duplicate_member=None):
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("AndroidManifest.xml", b"manifest-stub")
        for path in sorted(item for item in asset_root.rglob("*") if item.is_file()):
            name = "assets/" + path.relative_to(asset_root).as_posix()
            archive.write(path, name)
            if duplicate_member == name:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr(name, path.read_bytes())
        for name, value in (extra_members or {}).items():
            archive.writestr(name, value)


if __name__ == "__main__":
    unittest.main()
