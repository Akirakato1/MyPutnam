import hashlib
import importlib.util
import io
import json
import shutil
import tempfile
import unittest
import warnings
import zipfile
from copy import deepcopy
from pathlib import Path


ROOT = Path(__file__).parents[1]
REPOSITORY_ROOT = ROOT.parents[1]
AUDITOR_PATH = ROOT / "src" / "audit-packaged-content.py"


def load_auditor():
    spec = importlib.util.spec_from_file_location("audit_packaged_content", AUDITOR_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


AUDITOR = load_auditor()


class PackagedContentAuditTest(unittest.TestCase):
    def setUp(self):
        test_temp_root = REPOSITORY_ROOT / "build/test-temp"
        test_temp_root.mkdir(parents=True, exist_ok=True)
        self.temporary_directory = tempfile.TemporaryDirectory(dir=test_temp_root)
        self.root = Path(self.temporary_directory.name)
        self.assets = self.root / "assets"
        self.release, self.catalog = create_starter_bundle(self.assets)
        self.expected_release = self.root / "expected-release.json"
        write_json(self.expected_release, self.release)
        self.apk = self.root / "fixture.apk"
        write_apk(self.assets, self.apk)

    def tearDown(self):
        self.temporary_directory.cleanup()

    def audit(self):
        return AUDITOR.audit_packaged_apk(self.apk, self.expected_release)

    def rebuild(self, extra_members=None, duplicate_member=None):
        write_apk(self.assets, self.apk, extra_members, duplicate_member)

    def test_complete_package_backed_apk_is_audited_from_archive_bytes(self):
        report = self.audit()

        self.assertEqual("fixture-v0.5.0-development", report["releaseId"])
        self.assertEqual({"problemCount": 1, "referenceCount": 1, "packageCount": 2}, report["counts"])
        self.assertEqual(64, len(report["catalogSha256"]))

    def test_checked_in_starter_bundle_satisfies_the_development_contract(self):
        production_apk = self.root / "starter-fixture.apk"
        write_apk(REPOSITORY_ROOT / "app/src/main/assets", production_apk)

        report = AUDITOR.audit_packaged_apk(
            production_apk,
            REPOSITORY_ROOT / "content-src/releases/myputnam-v0.5.0-development.json",
        )

        self.assertEqual({"problemCount": 72, "referenceCount": 97, "packageCount": 169}, report["counts"])

    def test_missing_mutated_and_orphan_packages_are_rejected(self):
        descriptor = self.catalog["problems"][0]["package"]
        package_path = self.assets / "content-library/packages" / f"{descriptor['sha256']}.zip"
        package_path.unlink()
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "missing|package"):
            self.audit()

        self.reset_bundle()
        descriptor = self.catalog["problems"][0]["package"]
        package_path = self.assets / "content-library/packages" / f"{descriptor['sha256']}.zip"
        package_path.write_bytes(package_path.read_bytes() + b"tampered")
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "hash|byte|package"):
            self.audit()

        self.reset_bundle()
        (self.assets / "content-library/packages" / f"{'f' * 64}.zip").write_bytes(b"orphan")
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "orphan|unreferenced|unlisted"):
            self.audit()

    def test_catalog_hash_counts_and_embedded_release_drift_are_rejected(self):
        catalog_path = self.assets / "content-library/catalog.json"
        catalog_path.write_bytes(catalog_path.read_bytes() + b" ")
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "catalog.*hash|catalogSha256"):
            self.audit()

        self.reset_bundle()
        wrong = deepcopy(self.release)
        wrong["expectedPackageCount"] = 3
        write_json(self.expected_release, wrong)
        write_json(self.assets / "content-library/release.json", wrong)
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "count|expectedPackageCount"):
            self.audit()

        self.reset_bundle()
        embedded = deepcopy(self.release)
        embedded["appVersionCode"] = 99
        write_json(self.assets / "content-library/release.json", embedded)
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "release|appVersionCode|contract"):
            self.audit()

    def test_legacy_loose_content_and_database_are_rejected(self):
        for path in ("assets/content/putnam/2025/A1.html", "assets/databases/content.db"):
            with self.subTest(path=path):
                self.rebuild(extra_members={path: b"legacy"})
                with self.assertRaisesRegex(ValueError, "legacy|forbidden|loose"):
                    self.audit()

    def test_tampered_package_member_and_envelope_are_rejected(self):
        problem = self.catalog["problems"][0]
        descriptor = problem["package"]
        package_path = self.assets / "content-library/packages" / f"{descriptor['sha256']}.zip"
        replace_package_member(package_path, "documents/statement.fragment.html", b"tampered")
        update_catalog_descriptor(self.assets, self.release, problem["key"], package_path)
        write_json(self.expected_release, self.release)
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "member.*hash|statement|envelope"):
            self.audit()

    def test_missing_renderer_and_forbidden_development_files_are_rejected(self):
        (self.assets / "content-library/renderer/content.css").unlink()
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "renderer|content.css"):
            self.audit()

        self.reset_bundle()
        for path in (
            "assets/content-library/original/paper.pdf",
            "assets/content-library/review/note.txt",
            "res/raw/release-signing.p12",
            "META-INF/private-key.pem",
        ):
            with self.subTest(path=path):
                self.rebuild(extra_members={path: b"forbidden"})
                with self.assertRaisesRegex(ValueError, "Forbidden|forbidden|unlisted"):
                    self.audit()

    def test_unsafe_and_duplicate_apk_members_are_rejected_before_extraction(self):
        self.rebuild(extra_members={"../escape.txt": b"escape"})
        with self.assertRaisesRegex(ValueError, "Unsafe|traversal|path"):
            self.audit()

        self.rebuild(duplicate_member="assets/content-library/catalog.json")
        with self.assertRaisesRegex(ValueError, "Duplicate|duplicate"):
            self.audit()

    def test_powershell_verifier_uses_optional_manifest_and_package_auditor(self):
        verifier = (REPOSITORY_ROOT / "scripts/verify-release.ps1").read_text(encoding="utf-8")
        self.assertIn("audit-packaged-content.py", verifier)
        self.assertIn("ExpectedReleasePath", verifier)
        self.assertIn("ACCESS_NETWORK_STATE", verifier)
        self.assertIn("INTERNET", verifier)
        self.assertIn("networkSecurityConfig", verifier)
        self.assertIn("dump resources", verifier)
        self.assertIn("xml/network_security_config", verifier)
        self.assertNotIn("dump xmltree --file res/xml/network_security_config.xml", verifier)
        self.assertNotIn("no INTERNET permission", verifier)

    def reset_bundle(self):
        shutil.rmtree(self.assets, ignore_errors=True)
        self.release, self.catalog = create_starter_bundle(self.assets)
        write_json(self.expected_release, self.release)


def create_starter_bundle(root):
    package_root = root / "content-library/packages"
    renderer = root / "content-library/renderer"
    package_root.mkdir(parents=True)
    (renderer / "katex/fonts").mkdir(parents=True)
    (renderer / "document.html").write_text("<!doctype html><html></html>", encoding="utf-8")
    (renderer / "content.css").write_text("body{}", encoding="utf-8")
    (renderer / "katex/katex.min.css").write_text("@font-face{src:url(fonts/Test.woff2)}", encoding="utf-8")
    (renderer / "katex/fonts/Test.woff2").write_bytes(b"font")

    problem_package = make_package("problem", "putnam/2025/A1", 3, 1, {
        "source/problem.json": ("application/json", b"{}\n"),
        "documents/statement.fragment.html": ("text/html; charset=utf-8", b"<p>Problem</p>"),
    })
    reference_package = make_package("reference", "math-reference/algebra/example", 1, 2, {
        "source/reference.json": ("application/json", b"{}\n"),
        "documents/article.fragment.html": ("text/html; charset=utf-8", b"<p>Reference</p>"),
    })
    problem_descriptor = descriptor_for(problem_package, "problem")
    reference_descriptor = descriptor_for(reference_package, "reference")
    for archive, descriptor in ((problem_package, problem_descriptor), (reference_package, reference_descriptor)):
        (package_root / f"{descriptor['sha256']}.zip").write_bytes(archive)

    catalog = {
        "schemaVersion": 1,
        "catalogId": "fixture-catalog",
        "catalogRevision": "a" * 64,
        "generatedAt": "2026-10-20T00:00:00.000Z",
        "minAppVersionCode": 1,
        "collections": [{"id": "putnam", "title": "Putnam", "kind": "competition", "displayOrder": 1}],
        "topics": [{"id": "algebra", "label": "Algebra", "displayOrder": 1}],
        "referenceCategories": [{"id": "algebra", "label": "Algebra", "displayOrder": 1, "aliases": []}],
        "problems": [{
            "key": "putnam/2025/A1", "collectionId": "putnam", "setId": "2025", "problemId": "A1",
            "label": "2025 A1", "ordinal": 1, "topics": ["algebra"], "rights": rights(),
            "package": problem_descriptor,
        }],
        "references": [{
            "key": "math-reference/algebra/example", "categoryId": "algebra", "articleId": "example",
            "title": "Example", "displayOrder": 1, "aliases": [], "keywords": [], "rights": rights(),
            "package": reference_descriptor,
        }],
    }
    catalog_bytes = json_bytes(catalog)
    (root / "content-library/catalog.json").write_bytes(catalog_bytes)
    release = {
        "schemaVersion": 1,
        "releaseId": "fixture-v0.5.0-development",
        "distributionScope": "local-development",
        "appVersionName": "0.5.0-dev",
        "appVersionCode": 5,
        "catalogId": catalog["catalogId"],
        "catalogRevision": catalog["catalogRevision"],
        "catalogSha256": sha256(catalog_bytes),
        "generatedAt": catalog["generatedAt"],
        "feedRoot": "https://example.test/content/v1/",
        "minAppVersionCode": 1,
        "supportedSchemaVersions": {"catalog": [1], "package": [1], "problem": [3], "reference": [1], "richDocument": [1, 2]},
        "includedSets": [{"collectionId": "putnam", "setId": "2025"}],
        "includedReferenceCategories": ["algebra"],
        "expectedProblemCount": 1,
        "expectedReferenceCount": 1,
        "expectedPackageCount": 2,
        "allowedUnresolvedContentKeys": [],
    }
    write_json(root / "content-library/release.json", release)
    return release, catalog


def make_package(content_type, key, content_schema, rich_document, members):
    declarations = [
        {"path": path, "mediaType": media_type, "bytes": len(payload), "sha256": sha256(payload)}
        for path, (media_type, payload) in sorted(members.items())
    ]
    base = {
        "packageSchemaVersion": 1,
        "contentType": content_type,
        "contentKey": key,
        "contentSchemaVersion": content_schema,
        "richDocumentVersion": rich_document,
        "minAppVersionCode": 1,
        "declaredUncompressedBytes": sum(item["bytes"] for item in declarations),
        "members": declarations,
    }
    envelope = {**base, "packageRevision": sha256(canonical_json(base).encode("utf-8"))}
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        payloads = {**{path: value[1] for path, value in members.items()}, "package.json": json_bytes(envelope)}
        for path, payload in sorted(payloads.items()):
            info = zipfile.ZipInfo(path, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, payload)
    return output.getvalue()


def descriptor_for(archive, content_type):
    with zipfile.ZipFile(io.BytesIO(archive)) as package:
        envelope = json.loads(package.read("package.json"))
    digest = sha256(archive)
    return {
        "contentType": content_type,
        "packageRevision": envelope["packageRevision"],
        "packageSchemaVersion": 1,
        "contentSchemaVersion": envelope["contentSchemaVersion"],
        "richDocumentVersion": envelope["richDocumentVersion"],
        "path": f"packages/{content_type}/{digest[:2]}/{digest}.zip",
        "compressedBytes": len(archive),
        "uncompressedBytes": envelope["declaredUncompressedBytes"],
        "sha256": digest,
        "minAppVersionCode": 1,
    }


def replace_package_member(package_path, member_name, payload):
    with zipfile.ZipFile(package_path) as source:
        members = {name: source.read(name) for name in source.namelist()}
    members[member_name] = payload
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, value in sorted(members.items()):
            archive.writestr(name, value)
    package_path.write_bytes(output.getvalue())


def update_catalog_descriptor(root, release, key, package_path):
    catalog_path = root / "content-library/catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    item = next(item for item in catalog["problems"] if item["key"] == key)
    item["package"]["sha256"] = sha256(package_path.read_bytes())
    item["package"]["compressedBytes"] = package_path.stat().st_size
    replacement = root / "content-library/packages" / f"{item['package']['sha256']}.zip"
    package_path.rename(replacement)
    catalog_bytes = json_bytes(catalog)
    catalog_path.write_bytes(catalog_bytes)
    release["catalogSha256"] = sha256(catalog_bytes)
    write_json(root / "content-library/release.json", release)


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


def rights():
    return {
        "sourceUrl": "https://example.test/source", "attribution": "Example", "copyrightHolder": None,
        "licenseBasis": "test fixture", "redistributionStatus": "cleared",
    }


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def json_bytes(value):
    return (canonical_json(value) + "\n").encode("utf-8")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(json_bytes(value))


def sha256(value):
    return hashlib.sha256(value).hexdigest()


if __name__ == "__main__":
    unittest.main()
