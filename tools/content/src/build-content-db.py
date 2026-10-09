"""Build the immutable Room-compatible content database from content manifests."""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path


SCHEMA_PATH = Path(__file__).parents[1] / "sql" / "content-schema.sql"
CONTENT_SCHEMA_VERSION = 3


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def rooted_asset_path(collection_id, set_id, asset_path):
    return f"content/{collection_id}/{set_id}/{asset_path}"


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


SAFE_ID = re.compile(r"^[A-Za-z0-9._-]+$")
EXPECTED_HINT_STAGES = ("nudge", "strategy", "knowledge", "outline")


def build_content_database(manifest, catalog, output_path):
    """Atomically create one schema-v3 set database."""
    normalized_manifest = _load_json_value(manifest)
    normalized_catalog = _load_json_value(catalog)
    _validate_library_catalog(normalized_catalog)
    _validate_manifests([normalized_manifest], normalized_catalog)
    _build_database(
        [normalized_manifest],
        normalized_catalog,
        output_path,
        content_version=normalized_manifest["contentVersion"],
        build_kind="set",
        manifest_sha256=normalized_manifest["manifestSha256"],
    )


def build_archive_database(manifests, catalog, archive_catalog, output_path):
    """Atomically create the strict all-target schema-v3 archive database."""
    normalized_catalog = _load_json_value(catalog)
    normalized_archive = _load_json_value(archive_catalog)
    normalized_manifests = sorted(
        (_load_json_value(manifest) for manifest in manifests),
        key=lambda item: (item["collectionId"], item["setId"]),
    )
    _validate_library_catalog(normalized_catalog)
    _validate_archive_catalog(normalized_archive, normalized_manifests)
    _validate_manifests(normalized_manifests, normalized_catalog)
    _build_database(
        normalized_manifests,
        normalized_catalog,
        output_path,
        content_version=normalized_archive["contentVersion"],
        build_kind="archive",
        manifest_sha256=_sha256(normalized_manifests),
    )


def _build_database(manifests, catalog, output_path, *, content_version, build_kind, manifest_sha256):
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(
        dir=output_path.parent, prefix=f".{output_path.name}.", suffix=".tmp"
    )
    os.close(file_descriptor)
    temporary_path = Path(temporary_name)
    try:
        with closing(sqlite3.connect(temporary_path)) as database:
            with database:
                database.execute("PRAGMA foreign_keys = ON")
                database.executescript(SCHEMA_PATH.read_text(encoding="utf-8"))
                _insert_v3(database, manifests, catalog, content_version, build_kind, manifest_sha256)
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


def _validate_library_catalog(catalog):
    if catalog.get("schemaVersion") != 1 or catalog.get("contentSchemaVersion") != 3:
        raise ValueError("Library catalog must use schema 1 and content schema 3")
    _require_unique(catalog.get("collections", []), "id", "collection id")
    _require_unique(catalog.get("collections", []), "displayOrder", "collection display order")
    for collection in catalog.get("collections", []):
        _require_safe_id(collection.get("id"), "collection id")
        _require_unique(collection.get("sets", []), "id", f"set id in {collection.get('id')}")
        _require_unique(collection.get("sets", []), "displayOrder", f"set display order in {collection.get('id')}")
        for problem_set in collection.get("sets", []):
            _require_safe_id(problem_set.get("id"), "set id")
    topics = catalog.get("topics", [])
    if not topics:
        raise ValueError("Library catalog must contain topics")
    _require_unique(topics, "id", "topic id")
    _require_unique(topics, "displayOrder", "topic display order")
    for topic in topics:
        _require_safe_id(topic.get("id"), "topic id")


def _validate_archive_catalog(archive_catalog, manifests):
    if archive_catalog.get("contentSchemaVersion") != 3:
        raise ValueError("Archive catalog content schema version must be 3")
    targets = archive_catalog.get("targetYears")
    completed = archive_catalog.get("completedYears")
    if not isinstance(targets, list) or targets != sorted(set(targets)):
        raise ValueError("Archive target years must be unique and sorted")
    if completed != targets:
        raise ValueError("Archive catalog must be complete before building")
    manifest_years = []
    for manifest in manifests:
        if manifest.get("collectionId") != "putnam":
            raise ValueError("Strict historical archive accepts only putnam manifests")
        try:
            manifest_years.append(int(manifest.get("setId")))
        except (TypeError, ValueError):
            raise ValueError("Putnam archive set IDs must be competition years") from None
    if sorted(manifest_years) != targets:
        raise ValueError(f"Archive target years {targets} do not match manifest years {sorted(manifest_years)}")


def _validate_manifests(manifests, catalog):
    if not manifests:
        raise ValueError("At least one manifest is required")
    catalog_sets = {
        (collection["id"], problem_set["id"]): (collection, problem_set)
        for collection in catalog["collections"]
        for problem_set in collection.get("sets", [])
    }
    known_topics = {topic["id"] for topic in catalog["topics"]}
    expected_catalog_hash = _sha256(catalog)
    seen_problem_keys = set()
    seen_sets = set()
    for manifest in manifests:
        if manifest.get("schemaVersion") != CONTENT_SCHEMA_VERSION:
            raise ValueError("Manifest schema version must be 3")
        collection_id = manifest.get("collectionId")
        set_id = manifest.get("setId")
        _require_safe_id(collection_id, "manifest collectionId")
        _require_safe_id(set_id, "manifest setId")
        if (collection_id, set_id) not in catalog_sets:
            raise ValueError(f"Unknown manifest set {collection_id}/{set_id}")
        if (collection_id, set_id) in seen_sets:
            raise ValueError(f"Duplicate manifest set {collection_id}/{set_id}")
        seen_sets.add((collection_id, set_id))
        if manifest.get("catalogSha256") != expected_catalog_hash:
            raise ValueError("Manifest catalog hash does not match library catalog")
        unsigned = dict(manifest)
        supplied_manifest_hash = unsigned.pop("manifestSha256", None)
        if supplied_manifest_hash != _sha256(unsigned):
            raise ValueError("Manifest hash does not match manifest contents")
        asset_hashes = {asset.get("path"): asset.get("sha256") for asset in manifest.get("assets", [])}
        if len(asset_hashes) != len(manifest.get("assets", [])):
            raise ValueError("Manifest contains duplicate asset paths")
        for required_path in ("content/shared/content.css", "content/shared/katex/katex.min.css"):
            if required_path not in asset_hashes:
                raise ValueError(f"Manifest is missing required shared style: {required_path}")
        if not any(path.startswith("content/shared/katex/fonts/") for path in asset_hashes if isinstance(path, str)):
            raise ValueError("Manifest is missing required bundled KaTeX fonts")
        for path, digest in asset_hashes.items():
            if not isinstance(path, str) or not path.startswith("content/") or "\\" in path or "/../" in f"/{path}/":
                raise ValueError(f"Unsafe manifest asset path: {path}")
            if not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest):
                raise ValueError(f"Invalid manifest asset hash: {path}")
        ordinals = set()
        problem_ids = set()
        for problem in manifest.get("problems", []):
            key = problem.get("key")
            expected_key = f"{collection_id}/{set_id}/{problem.get('problemId')}"
            if key != expected_key:
                raise ValueError(f"Problem canonical key mismatch: {key} != {expected_key}")
            if key in seen_problem_keys:
                raise ValueError(f"Duplicate problem key: {key}")
            seen_problem_keys.add(key)
            if problem.get("collectionId") != collection_id or problem.get("setId") != set_id:
                raise ValueError(f"Problem {key} does not match its manifest set")
            _require_safe_id(problem.get("problemId"), "problemId")
            if problem.get("problemId") in problem_ids:
                raise ValueError(f"Duplicate problem ID in set: {problem.get('problemId')}")
            problem_ids.add(problem.get("problemId"))
            ordinal = problem.get("ordinal")
            if not isinstance(ordinal, int) or ordinal < 1 or ordinal in ordinals:
                raise ValueError(f"Invalid or duplicate ordinal in {collection_id}/{set_id}: {ordinal}")
            ordinals.add(ordinal)
            topics = problem.get("topics")
            if not isinstance(topics, list) or not topics or len(topics) != len(set(topics)):
                raise ValueError(f"Problem {key} must have unique nonempty topics")
            unknown_topics = sorted(set(topics) - known_topics)
            if unknown_topics:
                raise ValueError(f"Problem {key} has unknown topics: {unknown_topics}")
            hints = sorted(problem.get("hints", []), key=lambda item: item.get("order", 0))
            if [(hint.get("order"), hint.get("stage")) for hint in hints] != list(enumerate(EXPECTED_HINT_STAGES, 1)):
                raise ValueError(f"Problem {key} must contain the four canonical hint stages")
            prerequisites = sorted(problem.get("prerequisites", []), key=lambda item: item.get("order", 0))
            if not prerequisites or [item.get("order") for item in prerequisites] != list(range(1, len(prerequisites) + 1)):
                raise ValueError(f"Problem {key} must contain ordered prerequisites")
            for reference in [problem.get("statement"), problem.get("solution"), *hints, *prerequisites]:
                _validate_asset_reference(reference, asset_hashes, collection_id, set_id)


def _validate_asset_reference(reference, asset_hashes, collection_id, set_id):
    path = reference.get("assetPath") if isinstance(reference, dict) else None
    digest = reference.get("sha256") if isinstance(reference, dict) else None
    required_prefix = f"content/{collection_id}/{set_id}/"
    if not isinstance(path, str) or not path.startswith(required_prefix):
        raise ValueError(f"Problem asset path must begin with {required_prefix}: {path}")
    if asset_hashes.get(path) != digest:
        raise ValueError(f"Problem asset reference is missing or stale: {path}")


def _insert_v3(database, manifests, catalog, content_version, build_kind, manifest_sha256):
    installed_sets = {(manifest["collectionId"], manifest["setId"]) for manifest in manifests}
    installed_collection_ids = {collection_id for collection_id, _ in installed_sets}
    collections = sorted(
        (item for item in catalog["collections"] if item["id"] in installed_collection_ids),
        key=lambda item: (item["displayOrder"], item["id"]),
    )
    database.executemany(
        "INSERT INTO collection (id, title, kind, display_order) VALUES (?, ?, ?, ?)",
        [(item["id"], item["title"], item["kind"], item["displayOrder"]) for item in collections],
    )
    for collection in collections:
        sets = sorted(
            (item for item in collection.get("sets", []) if (collection["id"], item["id"]) in installed_sets),
            key=lambda item: (item["displayOrder"], item["id"]),
        )
        database.executemany(
            "INSERT INTO problem_set (collection_id, set_id, title, display_order, competition_year) "
            "VALUES (?, ?, ?, ?, ?)",
            [
                (
                    collection["id"], item["id"], item["title"], item["displayOrder"],
                    item.get("metadata", {}).get("competitionYear"),
                )
                for item in sets
            ],
        )
    topics = sorted(catalog["topics"], key=lambda item: (item["displayOrder"], item["id"]))
    database.executemany(
        "INSERT INTO topic (id, label, display_order) VALUES (?, ?, ?)",
        [(item["id"], item["label"], item["displayOrder"]) for item in topics],
    )
    for manifest in sorted(manifests, key=lambda item: (item["collectionId"], item["setId"])):
        for problem in sorted(manifest["problems"], key=lambda item: (item["ordinal"], item["problemId"])):
            _insert_problem(database, problem)

    problem_count = sum(len(manifest["problems"]) for manifest in manifests)
    topic_join_count = sum(len(problem["topics"]) for manifest in manifests for problem in manifest["problems"])
    hint_count = sum(len(problem["hints"]) for manifest in manifests for problem in manifest["problems"])
    prerequisite_count = sum(len(problem["prerequisites"]) for manifest in manifests for problem in manifest["problems"])
    metadata = (
        ("schema_version", str(CONTENT_SCHEMA_VERSION)),
        ("content_version", content_version),
        ("build_kind", build_kind),
        ("release_id", ""),
        ("catalog_sha256", _sha256(catalog)),
        ("manifest_sha256", manifest_sha256),
        ("collection_count", str(len(collections))),
        ("set_count", str(len(installed_sets))),
        ("topic_count", str(len(topics))),
        ("problem_count", str(problem_count)),
        ("problem_topic_count", str(topic_join_count)),
        ("hint_count", str(hint_count)),
        ("prerequisite_count", str(prerequisite_count)),
    )
    database.executemany("INSERT INTO content_meta (key, value) VALUES (?, ?)", metadata)


def _insert_problem(database, problem):
    key = problem["key"]
    database.execute(
        "INSERT INTO problem (key, collection_id, set_id, problem_id, label, ordinal, "
        "statement_asset_path, statement_sha256, solution_asset_path, solution_sha256, attribution_json) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            key, problem["collectionId"], problem["setId"], problem["problemId"], problem["label"],
            problem["ordinal"], problem["statement"]["assetPath"], problem["statement"]["sha256"],
            problem["solution"]["assetPath"], problem["solution"]["sha256"], canonical_json(problem["sources"]),
        ),
    )
    database.executemany(
        "INSERT INTO problem_topic (problem_key, topic_id) VALUES (?, ?)",
        [(key, topic_id) for topic_id in sorted(problem["topics"])],
    )
    database.executemany(
        "INSERT INTO hint (problem_key, hint_order, stage, title, asset_path, sha256) VALUES (?, ?, ?, ?, ?, ?)",
        [
            (key, item["order"], item["stage"], item["title"], item["assetPath"], item["sha256"])
            for item in sorted(problem["hints"], key=lambda candidate: candidate["order"])
        ],
    )
    database.executemany(
        "INSERT INTO prerequisite (problem_key, prerequisite_order, title, asset_path, sha256) VALUES (?, ?, ?, ?, ?)",
        [
            (key, item["order"], item["title"], item["assetPath"], item["sha256"])
            for item in sorted(problem["prerequisites"], key=lambda candidate: candidate["order"])
        ],
    )


def _require_unique(items, field, label):
    values = [item.get(field) for item in items]
    if len(values) != len(set(values)):
        raise ValueError(f"Duplicate {label}")


def _require_safe_id(value, label):
    if not isinstance(value, str) or value in (".", "..") or not SAFE_ID.fullmatch(value):
        raise ValueError(f"Unsafe {label}: {value}")


def _sha256(value):
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def parse_arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, action="append", required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--archive-catalog", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def main():
    arguments = parse_arguments()
    if arguments.archive_catalog:
        build_archive_database(
            arguments.manifest,
            arguments.catalog,
            arguments.archive_catalog,
            arguments.output,
        )
    else:
        if len(arguments.manifest) != 1:
            raise ValueError("multiple manifests require --archive-catalog")
        build_content_database(arguments.manifest[0], arguments.catalog, arguments.output)
    print(f"Built content database at {arguments.output}")


if __name__ == "__main__":
    main()
