"""Audit a staged or extracted immutable content bundle from its own bytes."""

import argparse
import hashlib
import json
import posixpath
import re
import sqlite3
from contextlib import closing
from pathlib import Path, PurePosixPath


HTML_DEPENDENCY = re.compile(r"(?:href|src)=[\"']([^\"']+)[\"']", re.IGNORECASE)
CSS_DEPENDENCY = re.compile(r"(?:url\(\s*[\"']?([^\"')\s]+)|@import\s+[\"']([^\"']+))", re.IGNORECASE)
FORBIDDEN_SUFFIXES = {".pdf", ".tex"}


def audit_content_bundle(asset_root, expected_release):
    root = Path(asset_root).resolve()
    expected = _load_json(expected_release)
    embedded_release = _read_json(root / "content/release.json", "embedded release manifest")
    if canonical_json(embedded_release) != canonical_json(expected):
        raise ValueError("Embedded release manifest does not match the expected release contract")
    catalog = _read_json(root / "content/library.json", "projected library catalog")
    included_sets = expected.get("includedSets")
    if not isinstance(included_sets, list) or included_sets != sorted(set(included_sets)):
        raise ValueError("Expected release selected sets must be unique and sorted")

    manifests = []
    expected_files = {
        "content/release.json",
        "content/library.json",
        "databases/content.db",
    }
    asset_records = {}
    for key in included_sets:
        segments = key.split("/")
        if len(segments) != 2:
            raise ValueError(f"Malformed selected release set: {key}")
        manifest_path = f"content/{segments[0]}/{segments[1]}/manifest.json"
        expected_files.add(manifest_path)
        manifest = _read_json(root / manifest_path, f"set manifest {key}")
        if f"{manifest.get('collectionId')}/{manifest.get('setId')}" != key:
            raise ValueError(f"Set manifest identity does not match selected release set {key}")
        unsigned = dict(manifest)
        supplied_hash = unsigned.pop("manifestSha256", None)
        if supplied_hash != sha_json(unsigned):
            raise ValueError(f"Set manifest hash mismatch: {key}")
        if manifest.get("catalogSha256") != sha_json(catalog):
            raise ValueError(f"Set manifest catalog hash mismatch: {key}")
        manifests.append(manifest)
        for asset in manifest.get("assets", []):
            path = asset.get("path")
            if not _safe_bundle_path(path):
                raise ValueError(f"Unsafe manifest asset path: {path}")
            previous = asset_records.get(path)
            if previous and previous != asset:
                raise ValueError(f"Conflicting duplicate manifest asset: {path}")
            asset_records[path] = asset
            expected_files.add(path)

    actual_files = _file_entries(root)
    actual_paths = {item["path"] for item in actual_files}
    for path in sorted(actual_paths):
        pure = PurePosixPath(path)
        if pure.suffix.lower() in FORBIDDEN_SUFFIXES or "original" in pure.parts or "review" in pure.parts:
            raise ValueError(f"Forbidden development source file in bundle: {path}")
    missing = sorted(expected_files - actual_paths)
    extra = sorted(actual_paths - expected_files)
    if missing:
        raise ValueError(f"Bundle is missing listed files: {missing}")
    if extra:
        raise ValueError(f"Bundle contains unlisted files: {extra}")

    actual_by_path = {item["path"]: item for item in actual_files}
    for path, record in sorted(asset_records.items()):
        actual = actual_by_path[path]
        if actual["sha256"] != record.get("sha256") or actual["bytes"] != record.get("bytes"):
            raise ValueError(f"Asset hash or byte length mismatch: {path}")
    _audit_dependencies(root, asset_records)

    problems = [problem for manifest in manifests for problem in manifest.get("problems", [])]
    counts = {
        "collectionCount": len(catalog.get("collections", [])),
        "setCount": len(manifests),
        "topicCount": len(catalog.get("topics", [])),
        "problemCount": len(problems),
        "hintCount": sum(len(problem.get("hints", [])) for problem in problems),
        "prerequisiteCount": sum(len(problem.get("prerequisites", [])) for problem in problems),
    }
    _audit_database(root / "databases/content.db", expected, catalog, manifests, counts)
    database = actual_by_path["databases/content.db"]
    return {
        "schemaVersion": 1,
        "releaseId": expected["releaseId"],
        "expectedReleaseSha256": sha_json(expected),
        "projectedCatalogSha256": sha_json(catalog),
        "databaseSha256": database["sha256"],
        "assetTreeSha256": sha_json(actual_files),
        "counts": counts,
        "files": actual_files,
    }


def _audit_database(path, release, catalog, manifests, counts):
    uri = path.resolve().as_uri() + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True)) as database:
        integrity = database.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise ValueError(f"SQLite integrity check failed: {integrity}")
        violations = database.execute("PRAGMA foreign_key_check").fetchall()
        if violations:
            raise ValueError(f"SQLite foreign-key or topic violations: {violations}")
        metadata = dict(database.execute("SELECT key, value FROM content_meta"))
        expected_metadata = {
            "schema_version": str(release["contentSchemaVersion"]),
            "content_version": release["contentVersion"],
            "build_kind": "release",
            "release_id": release["releaseId"],
            "catalog_sha256": sha_json(catalog),
            "manifest_sha256": sha_json(sorted(manifests, key=lambda item: (item["collectionId"], item["setId"]))),
            "collection_count": str(counts["collectionCount"]),
            "set_count": str(counts["setCount"]),
            "topic_count": str(counts["topicCount"]),
            "problem_count": str(counts["problemCount"]),
            "hint_count": str(counts["hintCount"]),
            "prerequisite_count": str(counts["prerequisiteCount"]),
        }
        for key, value in expected_metadata.items():
            if metadata.get(key) != value:
                raise ValueError(f"Database metadata mismatch for {key}: {metadata.get(key)} != {value}")
        database_sets = [f"{row[0]}/{row[1]}" for row in database.execute(
            "SELECT collection_id, set_id FROM problem_set ORDER BY collection_id, set_id"
        )]
        if database_sets != release["includedSets"]:
            raise ValueError(f"Database selected sets do not match release: {database_sets}")
        expected_keys = sorted(problem["key"] for manifest in manifests for problem in manifest["problems"])
        database_keys = [row[0] for row in database.execute("SELECT key FROM problem ORDER BY key")]
        if database_keys != expected_keys:
            raise ValueError("Database problem keys do not match embedded set manifests")
        expected_topics = sorted(
            (problem["key"], topic)
            for manifest in manifests for problem in manifest["problems"] for topic in problem["topics"]
        )
        database_topics = database.execute(
            "SELECT problem_key, topic_id FROM problem_topic ORDER BY problem_key, topic_id"
        ).fetchall()
        if database_topics != expected_topics:
            raise ValueError("Database topic joins do not match embedded set manifests")


def _audit_dependencies(root, asset_records):
    available = set(asset_records)
    for path in sorted(available):
        suffix = PurePosixPath(path).suffix.lower()
        if suffix not in {".html", ".css"}:
            continue
        text = (root / path).read_text(encoding="utf-8")
        if suffix == ".html":
            dependencies = HTML_DEPENDENCY.findall(text)
        else:
            dependencies = [left or right for left, right in CSS_DEPENDENCY.findall(text)]
        for dependency in dependencies:
            if dependency.startswith("#") or dependency.startswith("data:"):
                continue
            if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", dependency) or dependency.startswith("//"):
                raise ValueError(f"External dependency is forbidden in {path}: {dependency}")
            target = posixpath.normpath(posixpath.join(posixpath.dirname(path), dependency.split("#", 1)[0]))
            if target.startswith("../") or target.startswith("/"):
                raise ValueError(f"Dependency escapes the bundle in {path}: {dependency}")
            if target not in available:
                raise ValueError(f"Missing local dependency in {path}: {target}")


def _file_entries(root):
    output = []
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative_path = path.relative_to(root).as_posix()
        data = path.read_bytes()
        output.append({"path": relative_path, "bytes": len(data), "sha256": sha_bytes(data)})
    return output


def _safe_bundle_path(path):
    if not isinstance(path, str) or "\\" in path or path.startswith("/"):
        return False
    parts = PurePosixPath(path).parts
    return bool(parts) and all(part not in ("", ".", "..") for part in parts)


def _read_json(path, label):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read {label} at {path}: {error}") from error


def _load_json(value):
    if isinstance(value, (str, Path)):
        return _read_json(Path(value), "expected release manifest")
    return json.loads(json.dumps(value))


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def sha_json(value):
    return sha_bytes(canonical_json(value).encode("utf-8"))


def parse_arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--asset-root", required=True, type=Path)
    parser.add_argument("--expected-release", required=True, type=Path)
    parser.add_argument("--receipt-output", type=Path)
    return parser.parse_args()


def main():
    arguments = parse_arguments()
    report = audit_content_bundle(arguments.asset_root, arguments.expected_release)
    if arguments.receipt_output:
        arguments.receipt_output.parent.mkdir(parents=True, exist_ok=True)
        arguments.receipt_output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(
        f"Audited {report['counts']['problemCount']} problems across "
        f"{report['counts']['setCount']} sets"
    )


if __name__ == "__main__":
    main()
