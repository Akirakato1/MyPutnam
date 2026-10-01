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
    stages = {1: "nudge", 2: "strategy", 3: "knowledge", 4: "outline"}
    return {
        "year": 2025,
        "code": code,
        "ordinal": ordinal,
        "sourceRecord": f"{code}.json",
        "statement": asset(f"problems/{code}/statement.html", "a"),
        "hints": [
            {
                "order": order,
                "stage": stages[order],
                "title": f"Hint {order}",
                **asset(f"problems/{code}/hint-{order}.html", str(order)),
            }
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
    }


class BuildContentDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.output = Path(self.temporary_directory.name) / "content.db"
        self.manifest = {
            "schemaVersion": 2,
            "contentVersion": "2025.2",
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
                [
                    ("A1", 1, "nudge"),
                    ("A1", 2, "strategy"),
                    ("A1", 3, "knowledge"),
                    ("A1", 4, "outline"),
                ],
                database.execute(
                    "SELECT problem_code, hint_order, stage FROM hint "
                    "WHERE problem_code = 'A1' ORDER BY rowid"
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
            self.assertEqual(
                "2025.2",
                database.execute(
                    "SELECT value FROM content_meta WHERE key = 'contentVersion'"
                ).fetchone()[0],
            )
            self.assertEqual(2, database.execute("PRAGMA user_version").fetchone()[0])
            problem_columns = {
                row[1] for row in database.execute("PRAGMA table_info(problem)").fetchall()
            }
            self.assertFalse(any(column.startswith("original_pdf_") for column in problem_columns))
            self.assertEqual(
                "2",
                database.execute(
                    "SELECT value FROM content_meta WHERE key = 'contentSchemaVersion'"
                ).fetchone()[0],
            )

    def test_foreign_keys_reject_an_orphan_hint(self):
        BUILDER.build_content_database(self.manifest, self.output)

        with sqlite3.connect(self.output) as database:
            database.execute("PRAGMA foreign_keys = ON")
            with self.assertRaises(sqlite3.IntegrityError):
                database.execute(
                    "INSERT INTO hint "
                    "(year, problem_code, hint_order, stage, title, asset_path, sha256) "
                    "VALUES (2025, 'A6', 1, 'nudge', 'Orphan', 'orphan.html', ?)",
                    ("e" * 64,),
                )

    def test_hint_stage_is_required_and_builds_are_logically_deterministic(self):
        second_output = Path(self.temporary_directory.name) / "content-second.db"
        BUILDER.build_content_database(self.manifest, self.output)
        BUILDER.build_content_database(self.manifest, second_output)

        with sqlite3.connect(self.output) as database:
            database.execute("PRAGMA foreign_keys = ON")
            with self.assertRaises(sqlite3.IntegrityError):
                database.execute(
                    "INSERT INTO hint "
                    "(year, problem_code, hint_order, stage, title, asset_path, sha256) "
                    "VALUES (2025, 'A1', 5, NULL, 'Invalid', 'invalid.html', ?)",
                    ("f" * 64,),
                )
            first_dump = "\n".join(database.iterdump())

        with sqlite3.connect(second_output) as database:
            second_dump = "\n".join(database.iterdump())

        self.assertEqual(first_dump, second_dump)


if __name__ == "__main__":
    unittest.main()
