import hashlib
import importlib.util
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path


ROOT = Path(__file__).parents[1]
AUDITOR_PATH = ROOT / "src" / "content_bundle_audit.py"
DB_PATH = ROOT / "src" / "build-content-db.py"


def load_module(name, path):
    if not path.exists():
        return None
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


AUDITOR = load_module("content_bundle_audit", AUDITOR_PATH)
DB = load_module("release_db_builder", DB_PATH)


class ContentBundleAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name) / "assets"
        self.release, self.catalog, self.manifest = create_bundle(self.root)

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_coherent_bundle_reports_exact_logical_counts_and_hashes(self):
        self.assertIsNotNone(AUDITOR, "content_bundle_audit.py must exist")
        report = AUDITOR.audit_content_bundle(self.root, self.release)

        self.assertEqual("fixture-release", report["releaseId"])
        self.assertEqual(
            {
                "collectionCount": 1,
                "setCount": 1,
                "topicCount": 13,
                "problemCount": 1,
                "hintCount": 4,
                "prerequisiteCount": 1,
            },
            report["counts"],
        )
        self.assertEqual(64, len(report["assetTreeSha256"]))
        self.assertTrue(report["files"])

    def test_missing_tampered_external_and_forbidden_assets_are_rejected(self):
        self.assertIsNotNone(AUDITOR, "content_bundle_audit.py must exist")
        statement = self.root / "content/putnam/2025/problems/A1/statement.html"
        statement.unlink()
        with self.assertRaisesRegex(ValueError, "missing|statement"):
            AUDITOR.audit_content_bundle(self.root, self.release)

        self.temporary_directory.cleanup()
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name) / "assets"
        self.release, self.catalog, self.manifest = create_bundle(self.root)
        statement = self.root / "content/putnam/2025/problems/A1/statement.html"
        statement.write_text('<img src="https://example.test/leak.png">', encoding="utf-8")
        refresh_asset_hash(self.root, self.manifest, "content/putnam/2025/problems/A1/statement.html")
        write_manifest(self.root, self.manifest)
        with self.assertRaisesRegex(ValueError, "external|dependency"):
            AUDITOR.audit_content_bundle(self.root, self.release)

        (self.root / "content/original.tex").write_text("source", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "forbidden|tex|unlisted"):
            AUDITOR.audit_content_bundle(self.root, self.release)

    def test_orphan_topic_rows_and_release_selection_drift_are_rejected(self):
        self.assertIsNotNone(AUDITOR, "content_bundle_audit.py must exist")
        database_path = self.root / "databases/content.db"
        with closing(sqlite3.connect(database_path)) as database:
            database.execute("PRAGMA foreign_keys = OFF")
            database.execute("UPDATE problem_topic SET topic_id = 'orphan'")
            database.commit()
        with self.assertRaisesRegex(ValueError, "foreign|topic"):
            AUDITOR.audit_content_bundle(self.root, self.release)

        self.temporary_directory.cleanup()
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name) / "assets"
        self.release, self.catalog, self.manifest = create_bundle(self.root)
        wrong_release = dict(self.release)
        wrong_release["includedSets"] = ["putnam/2024"]
        with self.assertRaisesRegex(ValueError, "release|selected|embedded"):
            AUDITOR.audit_content_bundle(self.root, wrong_release)


def create_bundle(root):
    root.mkdir(parents=True)
    topic_ids = [
        "algebra", "analysis", "calculus", "combinatorics", "functional-equations",
        "geometry", "inequalities", "integrals", "linear-algebra", "number-theory",
        "polynomials", "probability", "sequences-series",
    ]
    catalog = {
        "schemaVersion": 1,
        "contentSchemaVersion": 3,
        "collections": [{
            "id": "putnam", "title": "Putnam", "kind": "competition", "displayOrder": 1,
            "sets": [{"id": "2025", "title": "2025", "displayOrder": 1,
                      "metadata": {"competitionYear": 2025}}],
        }],
        "topics": [
            {"id": topic_id, "label": topic_id, "displayOrder": index + 1}
            for index, topic_id in enumerate(topic_ids)
        ],
    }
    release = {
        "schemaVersion": 1,
        "releaseId": "fixture-release",
        "contentSchemaVersion": 3,
        "contentVersion": "fixture.1",
        "appVersionName": "0.4.0",
        "appVersionCode": 4,
        "includedSets": ["putnam/2025"],
    }
    prefix = "content/putnam/2025/problems/A1"
    paths = {
        "content/shared/content.css": b"body{}",
        "content/shared/katex/katex.min.css": b".katex{}",
        "content/shared/katex/fonts/KaTeX_Main-Regular.woff2": b"font",
        f"{prefix}/statement.html": b'<link href="../../../../shared/content.css"><p>Statement</p>',
        f"{prefix}/solution.html": b'<link href="../../../../shared/content.css"><p>Solution</p>',
        f"{prefix}/prerequisite-1.html": b'<link href="../../../../shared/content.css"><p>Prerequisite</p>',
    }
    for order in range(1, 5):
        paths[f"{prefix}/hint-{order}.html"] = (
            f'<link href="../../../../shared/content.css"><p>Hint {order}</p>'.encode()
        )
    for path, value in paths.items():
        destination = root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(value)

    def reference(path):
        return {"assetPath": path, "sha256": sha_bytes(paths[path])}

    problem = {
        "key": "putnam/2025/A1", "collectionId": "putnam", "setId": "2025",
        "problemId": "A1", "label": "A1", "ordinal": 1, "topics": ["algebra"],
        "sourceRecord": "A1.json", "statement": reference(f"{prefix}/statement.html"),
        "hints": [
            {"order": order, "stage": stage, "title": f"Hint {order}",
             **reference(f"{prefix}/hint-{order}.html")}
            for order, stage in enumerate(("nudge", "strategy", "knowledge", "outline"), 1)
        ],
        "prerequisites": [{"order": 1, "title": "Prerequisite",
                           **reference(f"{prefix}/prerequisite-1.html")}],
        "solution": reference(f"{prefix}/solution.html"),
        "sources": [{"kind": "problem", "label": "Source", "url": "https://example.test/p"}],
    }
    unsigned = {
        "schemaVersion": 3, "contentVersion": release["contentVersion"],
        "collectionId": "putnam", "setId": "2025", "problems": [problem],
        "assets": [
            {"path": path, "sha256": sha_bytes(value), "bytes": len(value)}
            for path, value in sorted(paths.items())
        ],
        "catalogSha256": sha_json(catalog),
    }
    manifest = {**unsigned, "manifestSha256": sha_json(unsigned)}
    write_json(root / "content/library.json", catalog)
    write_json(root / "content/release.json", release)
    write_manifest(root, manifest)
    DB.build_release_database([manifest], catalog, release, root / "databases/content.db")
    return release, catalog, manifest


def refresh_asset_hash(root, manifest, path):
    digest = sha_bytes((root / path).read_bytes())
    for asset in manifest["assets"]:
        if asset["path"] == path:
            asset["sha256"] = digest
            asset["bytes"] = (root / path).stat().st_size
    for problem in manifest["problems"]:
        for reference in [problem["statement"], problem["solution"], *problem["hints"], *problem["prerequisites"]]:
            if reference["assetPath"] == path:
                reference["sha256"] = digest
    unsigned = dict(manifest)
    unsigned.pop("manifestSha256", None)
    manifest["manifestSha256"] = sha_json(unsigned)


def write_manifest(root, manifest):
    write_json(root / "content/putnam/2025/manifest.json", manifest)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def sha_json(value):
    text = json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(text.encode()).hexdigest()


if __name__ == "__main__":
    unittest.main()
