import importlib.util
import hashlib
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


TOPIC_IDS = [
    "algebra", "analysis", "calculus", "combinatorics", "functional-equations",
    "geometry", "inequalities", "integrals", "linear-algebra", "number-theory",
    "polynomials", "probability", "sequences-series",
]


def library_catalog(years=(2024, 2025)):
    return {
        "schemaVersion": 1,
        "contentSchemaVersion": 3,
        "collections": [{
            "id": "putnam",
            "title": "Putnam",
            "kind": "competition",
            "displayOrder": 1,
            "sets": [
                {"id": str(year), "title": str(year), "displayOrder": index + 1,
                 "metadata": {"competitionYear": year}}
                for index, year in enumerate(sorted(years, reverse=True))
            ],
        }],
        "topics": [
            {"id": topic_id, "label": topic_id.replace("-", " ").title(), "displayOrder": index + 1}
            for index, topic_id in enumerate(TOPIC_IDS)
        ],
    }


def problem(code, ordinal, year=2025, topics=None):
    stages = {1: "nudge", 2: "strategy", 3: "knowledge", 4: "outline"}
    prefix = f"content/putnam/{year}/problems/{code}"
    return {
        "key": f"putnam/{year}/{code}",
        "collectionId": "putnam",
        "setId": str(year),
        "problemId": code,
        "label": code,
        "ordinal": ordinal,
        "topics": topics or ["algebra"],
        "sourceRecord": f"{code}.json",
        "statement": asset(f"{prefix}/statement.html", "a"),
        "hints": [
            {
                "order": order,
                "stage": stages[order],
                "title": f"Hint {order}",
                **asset(f"{prefix}/hint-{order}.html", str(order)),
            }
            for order in (4, 2, 1, 3)
        ],
        "prerequisites": [
            {"order": order, "title": f"Prerequisite {order}", **asset(f"{prefix}/prerequisite-{order}.html", "b")}
            for order in (2, 1)
        ],
        "solution": asset(f"{prefix}/solution.html", "c"),
        "sources": [
            {"kind": "problem", "label": "Official problems", "url": "https://example.test/problems"},
            {"kind": "solution", "label": "Official solutions", "url": "https://example.test/solutions"},
        ],
    }


def manifest(catalog, year, problems):
    assets = {
        "content/shared/content.css": {"path": "content/shared/content.css", "sha256": "d" * 64, "bytes": 1},
        "content/shared/katex/katex.min.css": {"path": "content/shared/katex/katex.min.css", "sha256": "e" * 64, "bytes": 1},
        "content/shared/katex/fonts/KaTeX_Main-Regular.woff2": {
            "path": "content/shared/katex/fonts/KaTeX_Main-Regular.woff2", "sha256": "f" * 64, "bytes": 1,
        },
    }
    for item in problems:
        for reference in [item["statement"], item["solution"], *item["hints"], *item["prerequisites"]]:
            assets[reference["assetPath"]] = {
                "path": reference["assetPath"],
                "sha256": reference["sha256"],
                "bytes": 1,
            }
    unsigned = {
        "schemaVersion": 3,
        "contentVersion": f"putnam-{year}.1",
        "collectionId": "putnam",
        "setId": str(year),
        "problems": problems,
        "assets": sorted(assets.values(), key=lambda item: item["path"]),
        "catalogSha256": BUILDER._sha256(catalog),
    }
    return {**unsigned, "manifestSha256": BUILDER._sha256(unsigned)}


def rehash(value):
    unsigned = dict(value)
    unsigned.pop("manifestSha256", None)
    value["manifestSha256"] = BUILDER._sha256(unsigned)
    return value


class BuildContentDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.output = Path(self.temporary_directory.name) / "content.db"
        self.catalog = library_catalog()
        self.manifest = manifest(
            self.catalog,
            2025,
            [problem("B1", 2, topics=["number-theory"]), problem("A1", 1, topics=["algebra", "analysis"])],
        )

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_rows_are_inserted_in_stable_problem_and_child_order(self):
        BUILDER.build_content_database(self.manifest, self.catalog, self.output)

        with sqlite3.connect(self.output) as database:
            self.assertEqual(
                [("putnam/2025/A1", 1), ("putnam/2025/B1", 2)],
                database.execute("SELECT key, ordinal FROM problem ORDER BY rowid").fetchall(),
            )
            self.assertEqual(
                [
                    ("putnam/2025/A1", 1, "nudge"),
                    ("putnam/2025/A1", 2, "strategy"),
                    ("putnam/2025/A1", 3, "knowledge"),
                    ("putnam/2025/A1", 4, "outline"),
                ],
                database.execute(
                    "SELECT problem_key, hint_order, stage FROM hint "
                    "WHERE problem_key = 'putnam/2025/A1' ORDER BY rowid"
                ).fetchall(),
            )
            self.assertEqual(
                [("putnam/2025/A1", 1), ("putnam/2025/A1", 2)],
                database.execute(
                    "SELECT problem_key, prerequisite_order FROM prerequisite "
                    "WHERE problem_key = 'putnam/2025/A1' ORDER BY rowid"
                ).fetchall(),
            )
            sources = database.execute(
                "SELECT attribution_json FROM problem WHERE key = 'putnam/2025/A1'"
            ).fetchone()[0]
            self.assertEqual(self.manifest["problems"][1]["sources"], json.loads(sources))
            self.assertEqual(
                "putnam-2025.1",
                database.execute(
                    "SELECT value FROM content_meta WHERE key = 'content_version'"
                ).fetchone()[0],
            )
            self.assertEqual(3, database.execute("PRAGMA user_version").fetchone()[0])
            problem_columns = {
                row[1] for row in database.execute("PRAGMA table_info(problem)").fetchall()
            }
            self.assertFalse(any(column.startswith("original_pdf_") for column in problem_columns))
            self.assertEqual(
                "3",
                database.execute(
                    "SELECT value FROM content_meta WHERE key = 'schema_version'"
                ).fetchone()[0],
            )

    def test_foreign_keys_reject_an_orphan_hint(self):
        BUILDER.build_content_database(self.manifest, self.catalog, self.output)

        with sqlite3.connect(self.output) as database:
            database.execute("PRAGMA foreign_keys = ON")
            with self.assertRaises(sqlite3.IntegrityError):
                database.execute(
                    "INSERT INTO hint "
                    "(problem_key, hint_order, stage, title, asset_path, sha256) "
                    "VALUES ('putnam/2025/A6', 1, 'nudge', 'Orphan', 'orphan.html', ?)",
                    ("e" * 64,),
                )

    def test_hint_stage_is_required_and_builds_are_logically_deterministic(self):
        second_output = Path(self.temporary_directory.name) / "content-second.db"
        BUILDER.build_content_database(self.manifest, self.catalog, self.output)
        BUILDER.build_content_database(self.manifest, self.catalog, second_output)

        with sqlite3.connect(self.output) as database:
            database.execute("PRAGMA foreign_keys = ON")
            with self.assertRaises(sqlite3.IntegrityError):
                database.execute(
                    "INSERT INTO hint "
                    "(problem_key, hint_order, stage, title, asset_path, sha256) "
                    "VALUES ('putnam/2025/A1', 5, NULL, 'Invalid', 'invalid.html', ?)",
                    ("f" * 64,),
                )
            first_dump = "\n".join(database.iterdump())

        with sqlite3.connect(second_output) as database:
            second_dump = "\n".join(database.iterdump())

        self.assertEqual(first_dump, second_dump)

    def test_schema_has_exact_normalized_tables_foreign_keys_and_named_indices(self):
        BUILDER.build_content_database(self.manifest, self.catalog, self.output)
        with sqlite3.connect(self.output) as database:
            tables = {
                row[0] for row in database.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
                )
            }
            self.assertEqual(
                {"collection", "problem_set", "topic", "problem", "problem_topic", "hint", "prerequisite", "content_meta"},
                tables,
            )
            indices = {
                row[0] for row in database.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'"
                )
            }
            self.assertTrue({
                "index_problem_collection_id_set_id_ordinal",
                "index_problem_topic_topic_id",
                "index_hint_problem_key",
                "index_prerequisite_problem_key",
            }.issubset(indices))
            self.assertEqual(13, database.execute("SELECT COUNT(*) FROM topic").fetchone()[0])
            self.assertEqual(0, database.execute("SELECT COUNT(*) FROM problem_topic WHERE topic_id = 'geometry'").fetchone()[0])
            self.assertEqual([], database.execute("PRAGMA foreign_key_check").fetchall())

    def test_direct_call_rejects_v2_unknown_topics_mismatched_keys_and_duplicate_ordinals_atomically(self):
        self.output.write_bytes(b"existing")
        invalid_cases = []

        v2 = dict(self.manifest)
        v2["schemaVersion"] = 2
        rehash(v2)
        invalid_cases.append(v2)

        unknown_topic = json.loads(json.dumps(self.manifest))
        unknown_topic["problems"][0]["topics"] = ["not-controlled"]
        rehash(unknown_topic)
        invalid_cases.append(unknown_topic)

        mismatched = json.loads(json.dumps(self.manifest))
        mismatched["problems"][0]["setId"] = "2024"
        rehash(mismatched)
        invalid_cases.append(mismatched)

        duplicate_ordinal = json.loads(json.dumps(self.manifest))
        duplicate_ordinal["problems"][1]["ordinal"] = duplicate_ordinal["problems"][0]["ordinal"]
        rehash(duplicate_ordinal)
        invalid_cases.append(duplicate_ordinal)

        for invalid in invalid_cases:
            with self.assertRaises(ValueError):
                BUILDER.build_content_database(invalid, self.catalog, self.output)
            self.assertEqual(b"existing", self.output.read_bytes())

    def test_rooted_asset_path_preserves_generic_segments(self):
        self.assertEqual(
            "content/course/unit-1/P1/statement.html",
            BUILDER.rooted_asset_path("course", "unit-1", "P1/statement.html"),
        )

    def test_missing_shared_styles_or_fonts_and_stale_problem_hashes_are_rejected(self):
        for missing_path in [
            "content/shared/content.css",
            "content/shared/katex/katex.min.css",
            "content/shared/katex/fonts/KaTeX_Main-Regular.woff2",
        ]:
            invalid = json.loads(json.dumps(self.manifest))
            invalid["assets"] = [asset for asset in invalid["assets"] if asset["path"] != missing_path]
            rehash(invalid)
            with self.assertRaises(ValueError):
                BUILDER.build_content_database(invalid, self.catalog, self.output)

        stale = json.loads(json.dumps(self.manifest))
        stale["problems"][0]["statement"]["sha256"] = "0" * 64
        rehash(stale)
        with self.assertRaisesRegex(ValueError, "missing or stale"):
            BUILDER.build_content_database(stale, self.catalog, self.output)

    def test_archive_database_contains_two_years_and_global_metadata(self):
        archive_catalog = {
            "schemaVersion": 1,
            "contentSchemaVersion": 3,
            "contentVersion": "archive-2024-2025.1",
            "targetYears": [2024, 2025],
            "completedYears": [2024, 2025],
        }
        manifests = [
            manifest(self.catalog, 2025, [problem("A1", 1, 2025)]),
            manifest(self.catalog, 2024, [problem("A1", 1, 2024)]),
        ]

        BUILDER.build_archive_database(manifests, self.catalog, archive_catalog, self.output)

        with sqlite3.connect(self.output) as database:
            self.assertEqual(
                [("putnam/2024/A1",), ("putnam/2025/A1",)],
                database.execute("SELECT key FROM problem ORDER BY rowid").fetchall(),
            )
            self.assertEqual(
                [
                    ("2024", "content/putnam/2024/problems/A1/statement.html"),
                    ("2025", "content/putnam/2025/problems/A1/statement.html"),
                ],
                database.execute(
                    "SELECT set_id, statement_asset_path FROM problem ORDER BY rowid"
                ).fetchall(),
            )
            metadata = dict(database.execute("SELECT key, value FROM content_meta"))

        self.assertEqual("3", metadata["schema_version"])
        self.assertEqual("archive-2024-2025.1", metadata["content_version"])
        self.assertEqual("archive", metadata["build_kind"])
        self.assertEqual("2", metadata["set_count"])
        self.assertEqual("2", metadata["problem_count"])
        self.assertEqual("13", metadata["topic_count"])
        self.assertEqual(
            BUILDER._sha256(self.catalog),
            metadata["catalog_sha256"],
        )
        self.assertEqual(
            BUILDER._sha256(sorted(manifests, key=lambda item: (item["collectionId"], item["setId"]))),
            metadata["manifest_sha256"],
        )

    def test_archive_row_order_and_digest_are_stable_for_reversed_manifest_input(self):
        archive_catalog = {
            "schemaVersion": 1,
            "contentSchemaVersion": 3,
            "contentVersion": "archive-2024-2025.1",
            "targetYears": [2024, 2025],
            "completedYears": [2024, 2025],
        }
        manifests = [manifest(self.catalog, year, [problem("B1", 2, year), problem("A1", 1, year)]) for year in (2025, 2024)]
        second_output = Path(self.temporary_directory.name) / "archive-second.db"

        BUILDER.build_archive_database(manifests, self.catalog, archive_catalog, self.output)
        BUILDER.build_archive_database(list(reversed(manifests)), self.catalog, archive_catalog, second_output)

        with sqlite3.connect(self.output) as database:
            first_dump = "\n".join(database.iterdump())
        with sqlite3.connect(second_output) as database:
            second_dump = "\n".join(database.iterdump())
        self.assertEqual(first_dump, second_dump)

    def test_archive_rejects_manifest_year_mismatches_and_duplicate_problem_keys(self):
        archive_catalog = {
            "schemaVersion": 1,
            "contentSchemaVersion": 3,
            "contentVersion": "archive-2024-2025.1",
            "targetYears": [2024, 2025],
            "completedYears": [2024, 2025],
        }
        missing_year = [manifest(self.catalog, 2025, [problem("A1", 1, 2025)])]
        with self.assertRaisesRegex(ValueError, "manifest years"):
            BUILDER.build_archive_database(missing_year, self.catalog, archive_catalog, self.output)

        duplicate_problem = [
            manifest(self.catalog, 2024, [problem("A1", 1, 2024), problem("A1", 2, 2024)]),
            manifest(self.catalog, 2025, [problem("A1", 1, 2025)]),
        ]
        with self.assertRaisesRegex(ValueError, "Duplicate problem key"):
            BUILDER.build_archive_database(duplicate_problem, self.catalog, archive_catalog, self.output)


if __name__ == "__main__":
    unittest.main()
