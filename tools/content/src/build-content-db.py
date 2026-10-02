"""Build the immutable Room-compatible content database from content manifests."""

import argparse
import hashlib
import json
import os
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path


SCHEMA_PATH = Path(__file__).parents[1] / "sql" / "content-schema.sql"
CONTENT_SCHEMA_VERSION = 2


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def rooted_asset_path(year, asset_path):
    return f"content/{year}/{asset_path}"


def build_content_database(manifest, output_path):
    """Atomically create a deterministic logical database from a Task 2 manifest."""
    if isinstance(manifest, (str, os.PathLike)):
        manifest = json.loads(Path(manifest).read_text(encoding="utf-8"))

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(
        dir=output_path.parent,
        prefix=f".{output_path.name}.",
        suffix=".tmp",
    )
    os.close(file_descriptor)
    temporary_path = Path(temporary_name)

    try:
        with closing(sqlite3.connect(temporary_path)) as database:
            with database:
                database.execute("PRAGMA foreign_keys = ON")
                database.executescript(SCHEMA_PATH.read_text(encoding="utf-8"))
                _insert_manifest(database, manifest)
                violations = database.execute("PRAGMA foreign_key_check").fetchall()
                if violations:
                    raise ValueError(f"Foreign-key violations: {violations}")
                integrity = database.execute("PRAGMA integrity_check").fetchone()[0]
                if integrity != "ok":
                    raise ValueError(f"SQLite integrity check failed: {integrity}")
        os.replace(temporary_path, output_path)
    except Exception:
        temporary_path.unlink(missing_ok=True)
        raise


def build_archive_database(manifests, catalog, output_path):
    """Atomically create a deterministic multi-year content database."""
    catalog = _load_json_value(catalog)
    normalized_manifests = sorted(
        (_load_json_value(manifest) for manifest in manifests),
        key=lambda manifest: manifest["year"],
    )
    _validate_archive_inputs(normalized_manifests, catalog)

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(
        dir=output_path.parent,
        prefix=f".{output_path.name}.",
        suffix=".tmp",
    )
    os.close(file_descriptor)
    temporary_path = Path(temporary_name)

    try:
        with closing(sqlite3.connect(temporary_path)) as database:
            with database:
                database.execute("PRAGMA foreign_keys = ON")
                database.executescript(SCHEMA_PATH.read_text(encoding="utf-8"))
                _insert_archive(database, normalized_manifests, catalog)
                violations = database.execute("PRAGMA foreign_key_check").fetchall()
                if violations:
                    raise ValueError(f"Foreign-key violations: {violations}")
                integrity = database.execute("PRAGMA integrity_check").fetchone()[0]
                if integrity != "ok":
                    raise ValueError(f"SQLite integrity check failed: {integrity}")
        os.replace(temporary_path, output_path)
    except Exception:
        temporary_path.unlink(missing_ok=True)
        raise


def _load_json_value(value):
    if isinstance(value, (str, os.PathLike)):
        return json.loads(Path(value).read_text(encoding="utf-8"))
    return value


def _validate_archive_inputs(manifests, catalog):
    if not manifests:
        raise ValueError("Archive requires at least one manifest")
    if catalog.get("contentSchemaVersion") != CONTENT_SCHEMA_VERSION:
        raise ValueError(
            "Catalog content schema version does not match database schema version "
            f"{CONTENT_SCHEMA_VERSION}"
        )

    target_years = catalog.get("targetYears")
    completed_years = catalog.get("completedYears")
    if not isinstance(target_years, list) or target_years != sorted(set(target_years)):
        raise ValueError("Catalog target years must be unique and sorted")
    if completed_years != target_years:
        raise ValueError("Catalog must be complete before building the archive database")

    manifest_years = [manifest.get("year") for manifest in manifests]
    if manifest_years != target_years:
        raise ValueError(
            f"Catalog target years {target_years} do not match manifest years {manifest_years}"
        )

    seen_keys = set()
    for manifest in manifests:
        _validate_manifest_header(manifest)
        for problem_record in manifest.get("problems", []):
            key = (problem_record.get("year"), problem_record.get("code"))
            if key in seen_keys:
                raise ValueError(f"Duplicate problem key: {key[0]} {key[1]}")
            seen_keys.add(key)
            if problem_record.get("year") != manifest["year"]:
                raise ValueError(
                    f"Problem {problem_record.get('code')} has year "
                    f"{problem_record.get('year')}, expected {manifest['year']}"
                )


def _insert_archive(database, manifests, catalog):
    years = [manifest["year"] for manifest in manifests]
    problem_count = sum(len(manifest.get("problems", [])) for manifest in manifests)
    catalog_json = canonical_json(catalog).encode("utf-8")
    archive_json = canonical_json({
        "catalog": catalog,
        "manifests": manifests,
    }).encode("utf-8")
    metadata = (
        ("contentSchemaVersion", str(CONTENT_SCHEMA_VERSION)),
        ("contentVersion", catalog["contentVersion"]),
        ("includedYears", canonical_json(years)),
        ("minimumYear", str(min(years))),
        ("maximumYear", str(max(years))),
        ("yearCount", str(len(years))),
        ("problemCount", str(problem_count)),
        ("catalogSha256", hashlib.sha256(catalog_json).hexdigest()),
        ("archiveSha256", hashlib.sha256(archive_json).hexdigest()),
    )
    database.executemany("INSERT INTO content_meta (key, value) VALUES (?, ?)", metadata)
    for manifest in manifests:
        _insert_manifest_rows(database, manifest)


def _insert_manifest(database, manifest):
    _validate_manifest_header(manifest)
    schema_version = manifest["schemaVersion"]
    year = manifest["year"]
    canonical_manifest = canonical_json(manifest).encode("utf-8")
    metadata = (
        ("contentSchemaVersion", str(schema_version)),
        ("contentVersion", manifest.get("contentVersion", f"{year}.2")),
        ("manifestSha256", hashlib.sha256(canonical_manifest).hexdigest()),
        ("year", str(year)),
    )
    database.executemany("INSERT INTO content_meta (key, value) VALUES (?, ?)", metadata)
    _insert_manifest_rows(database, manifest)


def _validate_manifest_header(manifest):
    schema_version = manifest["schemaVersion"]
    if schema_version != CONTENT_SCHEMA_VERSION:
        raise ValueError(
            f"Manifest schema version {schema_version} does not match "
            f"database schema version {CONTENT_SCHEMA_VERSION}"
        )
    if not isinstance(manifest.get("year"), int):
        raise ValueError("Manifest year must be an integer")


def _insert_manifest_rows(database, manifest):
    year = manifest["year"]
    for problem in sorted(manifest.get("problems", []), key=lambda item: (item["ordinal"], item["code"])):
        if problem["year"] != year:
            raise ValueError(f"Problem {problem['code']} has year {problem['year']}, expected {year}")
        database.execute(
            """
            INSERT INTO problem (
                year, code, ordinal, statement_asset_path, statement_sha256,
                solution_asset_path, solution_sha256, attribution_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                year,
                problem["code"],
                problem["ordinal"],
                rooted_asset_path(year, problem["statement"]["assetPath"]),
                problem["statement"]["sha256"],
                rooted_asset_path(year, problem["solution"]["assetPath"]),
                problem["solution"]["sha256"],
                canonical_json(problem["sources"]),
            ),
        )
        database.executemany(
            """
            INSERT INTO hint (year, problem_code, hint_order, stage, title, asset_path, sha256)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            [
                (
                    year,
                    problem["code"],
                    hint["order"],
                    hint["stage"],
                    hint["title"],
                    rooted_asset_path(year, hint["assetPath"]),
                    hint["sha256"],
                )
                for hint in sorted(problem["hints"], key=lambda item: item["order"])
            ],
        )
        database.executemany(
            """
            INSERT INTO prerequisite (
                year, problem_code, prerequisite_order, title, asset_path, sha256
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            [
                (
                    year,
                    problem["code"],
                    prerequisite["order"],
                    prerequisite["title"],
                    rooted_asset_path(year, prerequisite["assetPath"]),
                    prerequisite["sha256"],
                )
                for prerequisite in sorted(problem["prerequisites"], key=lambda item: item["order"])
            ],
        )


def parse_arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--manifest", type=Path, action="append")
    source.add_argument("--empty-year", type=int)
    parser.add_argument("--catalog", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def main():
    arguments = parse_arguments()
    if arguments.catalog:
        if not arguments.manifest:
            raise ValueError("--catalog requires at least one --manifest")
        build_archive_database(arguments.manifest, arguments.catalog, arguments.output)
    else:
        if arguments.manifest and len(arguments.manifest) != 1:
            raise ValueError("multiple --manifest arguments require --catalog")
        manifest = (
            arguments.manifest[0]
            if arguments.manifest
            else {
            "schemaVersion": CONTENT_SCHEMA_VERSION,
            "contentVersion": f"{arguments.empty_year}.2",
            "year": arguments.empty_year,
            "problems": [],
            "assets": [],
            }
        )
        build_content_database(manifest, arguments.output)
    print(f"Built content database at {arguments.output}")


if __name__ == "__main__":
    main()
