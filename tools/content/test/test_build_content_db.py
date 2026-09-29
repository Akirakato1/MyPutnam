import importlib.util
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path


BUILDER_PATH = Path(__file__).parents[1] / "src" / "build-content-db.py"
SPEC = importlib.util.spec_from_file_location("build_content_db", BUILDER_PATH)
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


def asset(path, fill):
    return {"assetPath": path, "sha256": fill * 64}


def problem(code, ordinal):
    return {
        "year": 2025,
        "code": code,
        "ordinal": ordinal,
        "sourceRecord": f"{code}.json",
        "statement": asset(f"problems/{code}/statement.html", "a"),
        "hints": [
            {"order": order, "title": f"Hint {order}", **asset(f"problems/{code}/hint-{order}.html", str(order))}
            for order in (4, 2, 1, 3)
        ],
        "prerequisites": [
            {"order": order, "title": f"Prerequisite {order}", **asset(f"problems/{code}/prerequisite-{order}.html", "b")}
            for order in (2, 1)
        ],
        "solution": asset(f"problems/{code}/solution.html", "c"),
        "sources": [
            {"kind": "problem", "label": "Official problems", "url": "https://example.test/problems"},
            {"kind": "solution", "label": "Official solutions", "url": "https://example.test/solutions"},
        ],
        "originalPdf": {
            **asset("original/2025-problems.pdf", "d"),
            "pageIndex": ordinal - 1,
        },
    }


class BuildContentDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.output = Path(self.temporary_directory.name) / "content.db"
        self.manifest = {
            "schemaVersion": 1,
            "year": 2025,
            "problems": [problem("B1", 2), problem("A1", 1)],
            "assets": [],
        }

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_rows_are_inserted_in_stable_problem_and_child_order(self):
        BUILDER.build_content_database(self.manifest, self.output)

        with sqlite3.connect(self.output) as database:
            self.assertEqual(
                [("A1", 1), ("B1", 2)],
                database.execute("SELECT code, ordinal FROM problem ORDER BY rowid").fetchall(),
            )
            self.assertEqual(
                [("A1", 1), ("A1", 2), ("A1", 3), ("A1", 4)],
                database.execute(
                    "SELECT problem_code, hint_order FROM hint WHERE problem_code = 'A1' ORDER BY rowid"
                ).fetchall(),
            )
            self.assertEqual(
                [("A1", 1), ("A1", 2)],
                database.execute(
                    "SELECT problem_code, prerequisite_order FROM prerequisite "
                    "WHERE problem_code = 'A1' ORDER BY rowid"
                ).fetchall(),
            )
            sources = database.execute(
                "SELECT attribution_json FROM problem WHERE code = 'A1'"
            ).fetchone()[0]
            self.assertEqual(self.manifest["problems"][1]["sources"], json.loads(sources))

    def test_foreign_keys_reject_an_orphan_hint(self):
        BUILDER.build_content_database(self.manifest, self.output)

        with sqlite3.connect(self.output) as database:
            database.execute("PRAGMA foreign_keys = ON")
            with self.assertRaises(sqlite3.IntegrityError):
                database.execute(
                    "INSERT INTO hint "
                    "(year, problem_code, hint_order, title, asset_path, sha256) "
                    "VALUES (2025, 'A6', 1, 'Orphan', 'orphan.html', ?)",
                    ("e" * 64,),
                )


if __name__ == "__main__":
    unittest.main()
