"""Audit the offline content embedded in an APK using only archive bytes."""

import argparse
import hashlib
import importlib.util
import json
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path, PurePosixPath


MAX_MEMBER_BYTES = 64 * 1024 * 1024
MAX_ASSET_BYTES = 256 * 1024 * 1024
TRAVEL_RELEASE_ID = "putnam-2020-2025-v0.4.0"
TRAVEL_INCLUDED_SETS = [f"putnam/{year}" for year in range(2020, 2026)]


def _load_bundle_auditor():
    path = Path(__file__).with_name("content_bundle_audit.py")
    spec = importlib.util.spec_from_file_location("packaged_content_bundle_audit", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BUNDLE_AUDITOR = _load_bundle_auditor()


def audit_packaged_apk(apk_path, expected_release_path):
    apk = Path(apk_path).resolve()
    expected_release_path = Path(expected_release_path).resolve()
    if not apk.is_file():
        raise ValueError(f"APK does not exist: {apk}")
    expected_release = _read_json(expected_release_path, "expected release contract")

    with tempfile.TemporaryDirectory(prefix="putnam-apk-audit-") as temporary:
        asset_root = Path(temporary) / "assets"
        asset_root.mkdir()
        _extract_asset_bytes(apk, asset_root)
        report = BUNDLE_AUDITOR.audit_content_bundle(asset_root, expected_release)

    audit_release_cardinality(expected_release, report["counts"])
    return {
        **report,
        "apkSha256": _sha_file(apk),
        "apkBytes": apk.stat().st_size,
    }


def audit_release_cardinality(release, counts):
    """Apply the immutable logical-size contract for named production releases."""
    if release.get("releaseId") != TRAVEL_RELEASE_ID:
        return
    if release.get("includedSets") != TRAVEL_INCLUDED_SETS:
        raise ValueError(
            f"{TRAVEL_RELEASE_ID} must contain exactly the six sets putnam/2020 through putnam/2025"
        )
    expected = {
        "collectionCount": 1,
        "setCount": 6,
        "topicCount": 13,
        "problemCount": 72,
        "hintCount": 288,
    }
    for name, value in expected.items():
        if counts.get(name) != value:
            raise ValueError(
                f"{TRAVEL_RELEASE_ID} must contain exactly {value} {name}; got {counts.get(name)}"
            )
    prerequisite_count = counts.get("prerequisiteCount")
    if not isinstance(prerequisite_count, int) or prerequisite_count < 96:
        raise ValueError(
            f"{TRAVEL_RELEASE_ID} prerequisite count must reflect the manifests and be at least 96; "
            f"got {prerequisite_count}"
        )


def _extract_asset_bytes(apk, destination):
    seen = set()
    normalized_seen = set()
    total_asset_bytes = 0
    try:
        archive = zipfile.ZipFile(apk)
    except (OSError, zipfile.BadZipFile) as error:
        raise ValueError(f"Cannot open APK ZIP archive {apk}: {error}") from error

    with archive:
        for member in archive.infolist():
            name = member.filename
            normalized = _validate_member_name(name)
            if name in seen or normalized in normalized_seen:
                raise ValueError(f"Duplicate ZIP member: {name}")
            seen.add(name)
            normalized_seen.add(normalized)
            if member.flag_bits & 0x1:
                raise ValueError(f"Encrypted ZIP member is forbidden: {name}")
            unix_mode = member.external_attr >> 16
            if unix_mode and stat.S_ISLNK(unix_mode):
                raise ValueError(f"Symbolic-link ZIP member is forbidden: {name}")
            if member.file_size > MAX_MEMBER_BYTES:
                raise ValueError(f"ZIP member exceeds size limit: {name}")
            if member.is_dir() or not normalized.startswith("assets/"):
                continue
            relative = normalized[len("assets/"):]
            if not relative:
                continue
            relative_path = PurePosixPath(relative)
            if (
                relative_path.suffix.lower() in {".pdf", ".tex"}
                or "original" in relative_path.parts
                or "review" in relative_path.parts
            ):
                raise ValueError(f"Forbidden development source file in APK: {name}")
            if not (relative.startswith("content/") or relative == "databases/content.db"):
                continue
            total_asset_bytes += member.file_size
            if total_asset_bytes > MAX_ASSET_BYTES:
                raise ValueError("APK asset payload exceeds the extraction size limit")
            target = destination.joinpath(*relative_path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
            if target.stat().st_size != member.file_size:
                raise ValueError(f"Extracted ZIP member length mismatch: {name}")


def _validate_member_name(name):
    if not isinstance(name, str) or not name or "\\" in name or name.startswith("/"):
        raise ValueError(f"Unsafe ZIP member path: {name!r}")
    pure = PurePosixPath(name)
    parts = pure.parts
    if not parts or any(part in ("", ".", "..") for part in parts) or ":" in parts[0]:
        raise ValueError(f"Unsafe ZIP member path traversal: {name}")
    normalized = pure.as_posix()
    if normalized != name.rstrip("/"):
        raise ValueError(f"Unsafe non-canonical ZIP member path: {name}")
    return normalized


def _read_json(path, label):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read {label} at {path}: {error}") from error


def _sha_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--expected-release", required=True, type=Path)
    parser.add_argument("--report-output", type=Path)
    return parser.parse_args()


def main():
    arguments = parse_arguments()
    report = audit_packaged_apk(arguments.apk, arguments.expected_release)
    if arguments.report_output:
        arguments.report_output.parent.mkdir(parents=True, exist_ok=True)
        arguments.report_output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(
        f"Audited packaged release {report['releaseId']}: "
        f"{report['counts']['problemCount']} problems, "
        f"{report['counts']['hintCount']} hints, "
        f"{report['counts']['prerequisiteCount']} prerequisites"
    )


if __name__ == "__main__":
    main()
