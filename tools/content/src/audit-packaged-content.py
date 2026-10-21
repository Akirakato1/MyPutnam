"""Audit the package-backed starter library embedded in a MyPutnam APK."""

import argparse
import hashlib
import json
import re
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path, PurePosixPath


MAX_APK_MEMBER_BYTES = 64 * 1024 * 1024
MAX_ASSET_BYTES = 256 * 1024 * 1024
MAX_PACKAGE_BYTES = 16 * 1024 * 1024
MAX_PACKAGE_MEMBER_BYTES = 16 * 1024 * 1024
MAX_PACKAGE_MEMBERS = 256
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SAFE_ID = re.compile(r"^[A-Za-z0-9._-]+$")
FORBIDDEN_SUFFIXES = {
    ".pdf", ".tex", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".pk8",
}
REQUIRED_RENDERER = {
    "renderer/document.html",
    "renderer/content.css",
    "renderer/katex/katex.min.css",
}


def audit_packaged_apk(apk_path, expected_release_path):
    apk = Path(apk_path).resolve()
    expected_release_path = Path(expected_release_path).resolve()
    if not apk.is_file():
        raise ValueError(f"APK does not exist: {apk}")
    expected_release = _read_json(expected_release_path, "expected release contract")
    _validate_release(expected_release)

    with tempfile.TemporaryDirectory(prefix="myputnam-apk-audit-") as temporary:
        asset_root = Path(temporary) / "content-library"
        asset_root.mkdir()
        _extract_content_library(apk, asset_root)
        report = audit_starter_library(asset_root, expected_release)

    return {
        **report,
        "apkSha256": _sha_file(apk),
        "apkBytes": apk.stat().st_size,
    }


def audit_starter_library(root, expected_release):
    root = Path(root).resolve()
    embedded_release = _read_json(root / "release.json", "embedded release contract")
    if embedded_release != expected_release:
        raise ValueError("Embedded release contract does not match the expected release manifest")

    catalog_path = root / "catalog.json"
    try:
        catalog_bytes = catalog_path.read_bytes()
    except OSError as error:
        raise ValueError(f"Cannot read embedded catalog: {error}") from error
    actual_catalog_hash = _sha_bytes(catalog_bytes)
    if actual_catalog_hash != expected_release["catalogSha256"]:
        raise ValueError(
            f"catalogSha256 mismatch: expected {expected_release['catalogSha256']}, got {actual_catalog_hash}"
        )
    catalog = _json_bytes(catalog_bytes, "embedded catalog")
    _validate_catalog_contract(catalog, expected_release)
    _audit_renderer(root)

    selected_problems = _selected_problems(catalog, expected_release)
    selected_references = _selected_references(catalog, expected_release)
    entries = [(item, "problem") for item in selected_problems] + [
        (item, "reference") for item in selected_references
    ]
    counts = {
        "problemCount": len(selected_problems),
        "referenceCount": len(selected_references),
        "packageCount": len(entries),
    }
    expected_counts = {
        "problemCount": expected_release["expectedProblemCount"],
        "referenceCount": expected_release["expectedReferenceCount"],
        "packageCount": expected_release["expectedPackageCount"],
    }
    for name, expected in expected_counts.items():
        if counts[name] != expected:
            raise ValueError(f"{name} count mismatch: expected {expected}, got {counts[name]}")

    descriptors = []
    seen_keys = set()
    seen_digests = set()
    for item, content_type in entries:
        key = item.get("key")
        if key in seen_keys:
            raise ValueError(f"Duplicate selected content key: {key}")
        seen_keys.add(key)
        descriptor = item.get("package")
        _validate_descriptor(descriptor, content_type, key)
        if descriptor["sha256"] in seen_digests:
            raise ValueError(f"Duplicate selected package digest: {descriptor['sha256']}")
        seen_digests.add(descriptor["sha256"])
        descriptors.append((key, descriptor))

    _validate_rights(entries, expected_release)
    package_root = root / "packages"
    if not package_root.is_dir():
        raise ValueError("Embedded package directory is missing")
    actual_archives = {
        path.name for path in package_root.iterdir() if path.is_file()
    }
    expected_archives = {f"{descriptor['sha256']}.zip" for _, descriptor in descriptors}
    missing = sorted(expected_archives - actual_archives)
    orphaned = sorted(actual_archives - expected_archives)
    if missing:
        raise ValueError(f"Embedded package is missing: {missing[0]}")
    if orphaned:
        raise ValueError(f"Orphan or unreferenced embedded package: {orphaned[0]}")
    if any(path.is_dir() for path in package_root.iterdir()):
        raise ValueError("Package directory contains an unlisted nested directory")

    for key, descriptor in descriptors:
        archive = package_root / f"{descriptor['sha256']}.zip"
        _audit_package_archive(archive, key, descriptor)

    _reject_unlisted_library_files(root, expected_archives)
    return {
        "releaseId": expected_release["releaseId"],
        "catalogId": catalog["catalogId"],
        "catalogRevision": catalog["catalogRevision"],
        "catalogSha256": actual_catalog_hash,
        "counts": counts,
    }


def _extract_content_library(apk, destination):
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
            if member.file_size > MAX_APK_MEMBER_BYTES:
                raise ValueError(f"ZIP member exceeds size limit: {name}")
            _reject_forbidden_apk_member(normalized)
            if normalized.startswith("assets/content/") or normalized == "assets/databases/content.db":
                raise ValueError(f"Legacy loose content is forbidden in the APK: {name}")
            if member.is_dir() or not normalized.startswith("assets/content-library/"):
                continue
            relative = normalized[len("assets/content-library/"):]
            if not relative:
                continue
            total_asset_bytes += member.file_size
            if total_asset_bytes > MAX_ASSET_BYTES:
                raise ValueError("APK content-library payload exceeds the extraction size limit")
            target = destination.joinpath(*PurePosixPath(relative).parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
            if target.stat().st_size != member.file_size:
                raise ValueError(f"Extracted ZIP member length mismatch: {name}")


def _audit_renderer(root):
    renderer = root / "renderer"
    if not renderer.is_dir():
        raise ValueError("Embedded renderer directory is missing")
    present = {
        path.relative_to(root).as_posix() for path in renderer.rglob("*") if path.is_file()
    }
    missing = sorted(REQUIRED_RENDERER - present)
    if missing:
        raise ValueError(f"Required renderer asset is missing: {missing[0]}")
    if not any(path.startswith("renderer/katex/fonts/") for path in present):
        raise ValueError("Renderer KaTeX font assets are missing")
    for relative in present:
        if not _safe_relative_path(relative):
            raise ValueError(f"Unsafe renderer path: {relative}")


def _audit_package_archive(path, expected_key, descriptor):
    try:
        archive_bytes = path.read_bytes()
    except OSError as error:
        raise ValueError(f"Cannot read package {expected_key}: {error}") from error
    if len(archive_bytes) > MAX_PACKAGE_BYTES:
        raise ValueError(f"Package exceeds compressed size limit: {expected_key}")
    if len(archive_bytes) != descriptor["compressedBytes"]:
        raise ValueError(f"Package byte length does not match descriptor: {expected_key}")
    if _sha_bytes(archive_bytes) != descriptor["sha256"]:
        raise ValueError(f"Package hash does not match descriptor: {expected_key}")

    try:
        package = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as error:
        raise ValueError(f"Invalid package ZIP for {expected_key}: {error}") from error
    with package:
        infos = package.infolist()
        if len(infos) > MAX_PACKAGE_MEMBERS:
            raise ValueError(f"Package contains too many members: {expected_key}")
        names = []
        folded = set()
        payloads = {}
        for info in infos:
            name = _validate_package_member_name(info.filename)
            lower = name.lower()
            if lower in folded:
                raise ValueError(f"Duplicate package member: {name}")
            folded.add(lower)
            names.append(name)
            if info.is_dir():
                raise ValueError(f"Package directory entries are forbidden: {name}")
            if info.flag_bits & 0x1:
                raise ValueError(f"Encrypted package member is forbidden: {name}")
            mode = info.external_attr >> 16
            if mode and stat.S_ISLNK(mode):
                raise ValueError(f"Package symlink is forbidden: {name}")
            if info.file_size > MAX_PACKAGE_MEMBER_BYTES:
                raise ValueError(f"Package member exceeds size limit: {name}")
            try:
                payloads[name] = package.read(info)
            except (OSError, zipfile.BadZipFile, RuntimeError) as error:
                raise ValueError(f"Cannot read package member {name}: {error}") from error
        if names != sorted(names):
            raise ValueError(f"Package entries are not sorted: {expected_key}")

    if "package.json" not in payloads:
        raise ValueError(f"Package envelope is missing: {expected_key}")
    envelope = _json_bytes(payloads.pop("package.json"), f"package envelope {expected_key}")
    _validate_envelope(envelope, expected_key, descriptor)
    declarations = envelope["members"]
    declared = {}
    total = 0
    for member in declarations:
        member_path = member.get("path")
        if member_path in declared:
            raise ValueError(f"Duplicate package envelope member: {member_path}")
        _validate_package_member_name(member_path)
        _validate_member_contract(member)
        declared[member_path] = member
        total += member["bytes"]
    if set(payloads) != set(declared):
        missing = sorted(set(declared) - set(payloads))
        extra = sorted(set(payloads) - set(declared))
        detail = missing[0] if missing else extra[0]
        raise ValueError(f"Package has a missing or undeclared member: {detail}")
    if total != envelope["declaredUncompressedBytes"] or total != descriptor["uncompressedBytes"]:
        raise ValueError(f"Package uncompressed byte count does not match envelope: {expected_key}")
    for name, payload in payloads.items():
        declaration = declared[name]
        if len(payload) != declaration["bytes"]:
            raise ValueError(f"Package member byte length mismatch: {name}")
        if _sha_bytes(payload) != declaration["sha256"]:
            raise ValueError(f"Package member hash mismatch: {name}")
        if name.startswith("source/"):
            _json_bytes(payload, f"package source {name}")
        elif name.startswith("documents/"):
            try:
                source = payload.decode("utf-8")
            except UnicodeDecodeError as error:
                raise ValueError(f"Package document is not UTF-8: {name}") from error
            if re.search(r"<(script|iframe|object|embed|style)\b|\son[a-z]+\s*=", source, re.I):
                raise ValueError(f"Package document contains active content: {name}")


def _validate_envelope(envelope, expected_key, descriptor):
    required = {
        "packageSchemaVersion", "contentType", "contentKey", "contentSchemaVersion",
        "richDocumentVersion", "minAppVersionCode", "declaredUncompressedBytes", "members",
        "packageRevision",
    }
    if set(envelope) != required:
        raise ValueError(f"Package envelope fields are invalid: {expected_key}")
    if envelope["contentKey"] != expected_key:
        raise ValueError(f"Package envelope contentKey mismatch: {expected_key}")
    pairs = {
        "contentType": "contentType",
        "packageRevision": "packageRevision",
        "packageSchemaVersion": "packageSchemaVersion",
        "contentSchemaVersion": "contentSchemaVersion",
        "richDocumentVersion": "richDocumentVersion",
        "minAppVersionCode": "minAppVersionCode",
    }
    for envelope_name, descriptor_name in pairs.items():
        if envelope[envelope_name] != descriptor[descriptor_name]:
            raise ValueError(f"Package envelope does not match descriptor field {envelope_name}: {expected_key}")
    revision_payload = dict(envelope)
    revision = revision_payload.pop("packageRevision")
    computed = _sha_bytes(_canonical_json(revision_payload).encode("utf-8"))
    if revision != computed:
        raise ValueError(f"Package envelope revision mismatch: {expected_key}")
    if not isinstance(envelope["members"], list) or not envelope["members"]:
        raise ValueError(f"Package envelope members are invalid: {expected_key}")


def _validate_member_contract(member):
    if not isinstance(member, dict) or set(member) != {"path", "mediaType", "bytes", "sha256"}:
        raise ValueError("Package member declaration fields are invalid")
    path = member["path"]
    media_type = member["mediaType"]
    if path.startswith("source/") and path.endswith(".json"):
        expected_type = "application/json"
    elif path.startswith("documents/") and path.endswith(".fragment.html"):
        expected_type = "text/html; charset=utf-8"
    elif path.startswith("assets/"):
        expected_type = {
            ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg",
            ".jpeg": "image/jpeg", ".webp": "image/webp",
        }.get(Path(path).suffix.lower())
        if expected_type is None:
            raise ValueError(f"Unsupported package asset: {path}")
    else:
        raise ValueError(f"Unsupported package member path: {path}")
    if media_type != expected_type:
        raise ValueError(f"Package member media type mismatch: {path}")
    if not isinstance(member["bytes"], int) or member["bytes"] < 0:
        raise ValueError(f"Package member byte count is invalid: {path}")
    if not isinstance(member["sha256"], str) or not SHA256.fullmatch(member["sha256"]):
        raise ValueError(f"Package member hash is invalid: {path}")


def _validate_release(release):
    required = {
        "schemaVersion", "releaseId", "distributionScope", "appVersionName", "appVersionCode",
        "catalogId", "catalogRevision", "catalogSha256", "generatedAt", "feedRoot",
        "minAppVersionCode", "supportedSchemaVersions", "includedSets",
        "includedReferenceCategories", "expectedProblemCount", "expectedReferenceCount",
        "expectedPackageCount", "allowedUnresolvedContentKeys",
    }
    if not isinstance(release, dict) or set(release) != required:
        raise ValueError("Expected release manifest has invalid fields")
    if release["schemaVersion"] != 1:
        raise ValueError("Expected release manifest schemaVersion must be 1")
    if release["distributionScope"] not in {"local-development", "public-release"}:
        raise ValueError("Expected release manifest has invalid distributionScope")
    for field in ("catalogRevision", "catalogSha256"):
        if not isinstance(release[field], str) or not SHA256.fullmatch(release[field]):
            raise ValueError(f"Expected release manifest has invalid {field}")
    for field in ("appVersionCode", "minAppVersionCode", "expectedProblemCount", "expectedReferenceCount", "expectedPackageCount"):
        if not isinstance(release[field], int) or release[field] < 0:
            raise ValueError(f"Expected release manifest has invalid {field}")
    if release["appVersionCode"] < release["minAppVersionCode"]:
        raise ValueError("Expected release appVersionCode is below minAppVersionCode")


def _validate_catalog_contract(catalog, release):
    required = {
        "schemaVersion", "catalogId", "catalogRevision", "generatedAt", "minAppVersionCode",
        "collections", "topics", "referenceCategories", "problems", "references",
    }
    if not isinstance(catalog, dict) or set(catalog) != required:
        raise ValueError("Embedded catalog fields are invalid")
    for field in ("catalogId", "catalogRevision", "generatedAt", "minAppVersionCode"):
        if catalog[field] != release[field]:
            raise ValueError(f"Embedded catalog does not match release field {field}")
    if catalog["schemaVersion"] not in release["supportedSchemaVersions"].get("catalog", []):
        raise ValueError("Embedded catalog schemaVersion is unsupported")
    for field in ("collections", "topics", "referenceCategories", "problems", "references"):
        if not isinstance(catalog[field], list):
            raise ValueError(f"Embedded catalog {field} must be an array")


def _selected_problems(catalog, release):
    included = {
        (item.get("collectionId"), item.get("setId")) for item in release["includedSets"]
        if isinstance(item, dict)
    }
    if len(included) != len(release["includedSets"]):
        raise ValueError("Release includedSets contains invalid or duplicate entries")
    return [item for item in catalog["problems"] if (item.get("collectionId"), item.get("setId")) in included]


def _selected_references(catalog, release):
    included = set(release["includedReferenceCategories"])
    if len(included) != len(release["includedReferenceCategories"]):
        raise ValueError("Release includedReferenceCategories contains duplicates")
    return [item for item in catalog["references"] if item.get("categoryId") in included]


def _validate_rights(entries, release):
    unresolved = {
        item.get("key") for item, _ in entries
        if item.get("rights", {}).get("redistributionStatus") != "cleared"
    }
    allowed = set(release["allowedUnresolvedContentKeys"])
    if release["distributionScope"] == "public-release" and unresolved:
        raise ValueError(f"Public release contains uncleared content: {sorted(unresolved)[0]}")
    if unresolved != allowed:
        raise ValueError("Release unresolved-rights allowlist does not match selected catalog entries")


def _validate_descriptor(descriptor, expected_type, key):
    required = {
        "contentType", "packageRevision", "packageSchemaVersion", "contentSchemaVersion",
        "richDocumentVersion", "path", "compressedBytes", "uncompressedBytes", "sha256",
        "minAppVersionCode",
    }
    if not isinstance(descriptor, dict) or set(descriptor) != required:
        raise ValueError(f"Invalid package descriptor fields: {key}")
    if descriptor["contentType"] != expected_type:
        raise ValueError(f"Package descriptor contentType mismatch: {key}")
    for field in ("packageRevision", "sha256"):
        if not isinstance(descriptor[field], str) or not SHA256.fullmatch(descriptor[field]):
            raise ValueError(f"Package descriptor {field} is invalid: {key}")
    if not _safe_relative_path(descriptor["path"]) or not descriptor["path"].startswith("packages/") or not descriptor["path"].endswith(".zip"):
        raise ValueError(f"Package descriptor path is unsafe: {key}")
    for field in ("compressedBytes", "uncompressedBytes"):
        if not isinstance(descriptor[field], int) or descriptor[field] < 0:
            raise ValueError(f"Package descriptor {field} is invalid: {key}")


def _reject_unlisted_library_files(root, expected_archives):
    allowed = {"catalog.json", "release.json"}
    allowed.update(f"packages/{name}" for name in expected_archives)
    allowed.update(
        path.relative_to(root).as_posix()
        for path in (root / "renderer").rglob("*") if path.is_file()
    )
    actual = {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file()}
    extras = sorted(actual - allowed)
    if extras:
        raise ValueError(f"Unlisted content-library file: {extras[0]}")


def _reject_forbidden_apk_member(path):
    pure = PurePosixPath(path)
    lowered = {part.lower() for part in pure.parts}
    if (
        pure.suffix.lower() in FORBIDDEN_SUFFIXES
        or "original" in lowered
        or "review" in lowered
        or pure.name.lower() in {"keystore.properties", "key.properties", "signing.properties"}
    ):
        raise ValueError(f"Forbidden development or signing file in APK: {path}")


def _validate_member_name(name):
    if not isinstance(name, str) or not name or "\\" in name or name.startswith("/"):
        raise ValueError(f"Unsafe ZIP member path: {name!r}")
    pure = PurePosixPath(name.rstrip("/"))
    if any(part in ("", ".", "..") for part in pure.parts) or (pure.parts and ":" in pure.parts[0]):
        raise ValueError(f"Unsafe ZIP member path traversal: {name}")
    if pure.as_posix() != name.rstrip("/"):
        raise ValueError(f"Unsafe non-canonical ZIP member path: {name}")
    return pure.as_posix()


def _validate_package_member_name(name):
    normalized = _validate_member_name(name)
    if normalized != "package.json" and not (
        normalized.startswith("source/")
        or normalized.startswith("documents/")
        or normalized.startswith("assets/")
    ):
        raise ValueError(f"Unsupported package member path: {name}")
    if "?" in normalized or "#" in normalized:
        raise ValueError(f"Unsafe package member path: {name}")
    return normalized


def _safe_relative_path(value):
    if not isinstance(value, str) or not value or "\\" in value or value.startswith("/") or ":" in value or "?" in value or "#" in value:
        return False
    parts = PurePosixPath(value).parts
    return bool(parts) and all(part not in ("", ".", "..") for part in parts)


def _json_bytes(value, label):
    try:
        return json.loads(value.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot parse {label}: {error}") from error


def _read_json(path, label):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read {label} at {path}: {error}") from error


def _canonical_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


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
        f"{report['counts']['referenceCount']} references, "
        f"{report['counts']['packageCount']} packages"
    )


if __name__ == "__main__":
    main()
