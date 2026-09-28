#!/usr/bin/env python3
"""Fail-closed intake and evidence helpers for real NIS H2 database validation.

This tool does not open a database, run Flyway, or perform H2 conversion. It
validates operator-supplied provenance, verifies immutable source hashes, makes
a disposable copy, and compares deterministic state-fingerprint JSON files.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import stat
import sys
import subprocess
import zlib
from datetime import datetime
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
NETWORK_VERSION = {"mainnet": "0x68", "testnet": "0x98"}
HEX_256 = set("0123456789abcdef")
FINGERPRINT_KEYS = (
    "format",
    "network",
    "network_version",
    "genesis_block_hash",
    "height",
    "tip_block_hash",
    "counts",
    "schema",
    "tables",
)


class ValidationError(Exception):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def _is_hash(value: Any) -> bool:
    return isinstance(value, str) and len(value) == 64 and set(value.lower()) <= HEX_256


def _timestamp(value: Any, field: str) -> None:
    _require(isinstance(value, str) and bool(value.strip()), f"{field} is required")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as ex:
        raise ValidationError(f"{field} must be an ISO-8601 timestamp") from ex
    _require(parsed.tzinfo is not None, f"{field} must include a timezone")


def _inside(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def directory_fingerprint(files: list[dict[str, Any]]) -> str:
    """Hash a sorted manifest of relative path, size, and file SHA-256."""
    digest = hashlib.sha256()
    for item in sorted(files, key=lambda entry: entry["path"]):
        line = f"{item['path']}\0{item['size_bytes']}\0{item['sha256'].lower()}\n"
        digest.update(line.encode("utf-8"))
    return digest.hexdigest()


def inventory_artifact(artifact_dir: Path) -> dict[str, Any]:
    _require(not artifact_dir.is_symlink(), "artifact directory itself must not be a symlink")
    root = artifact_dir.resolve(strict=True)
    _require(root.is_dir(), "artifact directory must exist")
    _require(not _inside(root, REPO_ROOT), "source artifact must be outside the Git repository")
    files = []
    for path in sorted(root.rglob("*")):
        _require(not path.is_symlink(), f"symlinks are not accepted in artifact: {path.relative_to(root)}")
        if path.is_file():
            relative = path.relative_to(root).as_posix()
            files.append({"path": relative, "size_bytes": path.stat().st_size, "sha256": _sha256(path)})
    _require(any(item["path"].lower().endswith((".mv.db", ".h2.db", ".data.db")) for item in files),
             "artifact directory contains no recognized H2 database file")
    return {"files": files, "directory_fingerprint_sha256": directory_fingerprint(files)}


def validate_manifest(manifest: dict[str, Any], artifact_dir: Path, require_identity: bool = True) -> dict[str, Any]:
    _require(manifest.get("manifest_version") == 1, "manifest_version must be 1")
    network = manifest.get("network")
    _require(isinstance(network, str) and network in NETWORK_VERSION, "network must be mainnet or testnet")
    _require(manifest.get("classification") == "production", "synthetic/non-production artifacts are rejected")
    _require(manifest.get("complete_file_inventory") is True, "complete_file_inventory must be attested true")

    provenance = manifest.get("provenance")
    _require(isinstance(provenance, dict), "provenance object is required")
    required_provenance = ["source", "expected_genesis_evidence", "quiesced_evidence"]
    if require_identity:
        required_provenance.append("observed_genesis_evidence")
    for key in required_provenance:
        _require(isinstance(provenance.get(key), str) and bool(provenance[key].strip()), f"provenance.{key} is required")
    _timestamp(provenance.get("acquisition_date"), "provenance.acquisition_date")
    _timestamp(provenance.get("snapshot_date"), "provenance.snapshot_date")
    _require(provenance.get("quiesced") is True, "artifact must be attested as quiesced")
    _require(isinstance(provenance.get("snapshot_height"), int) and not isinstance(provenance["snapshot_height"], bool)
             and provenance["snapshot_height"] >= 1,
             "provenance.snapshot_height must be a positive integer")
    for key in ("snapshot_block_hash", "expected_genesis_block_hash"):
        _require(_is_hash(provenance.get(key)), f"provenance.{key} must be a 32-byte hex hash")
    if require_identity:
        for key in ("observed_tip_block_hash", "observed_genesis_block_hash"):
            _require(_is_hash(provenance.get(key)), f"provenance.{key} must be a 32-byte hex hash")
        _require(isinstance(provenance.get("observed_chain_height"), int)
                 and not isinstance(provenance.get("observed_chain_height"), bool)
                 and provenance["observed_chain_height"] == provenance["snapshot_height"],
                 "observed database height must equal the attested snapshot height")
        _require(provenance["snapshot_block_hash"].lower() == provenance["observed_tip_block_hash"].lower(),
                 "database tip hash does not match the attested snapshot block hash")
        _require(provenance["expected_genesis_block_hash"].lower() == provenance["observed_genesis_block_hash"].lower(),
                 "observed genesis block hash does not match independently sourced expected genesis")
        _require(provenance.get("observed_network") == network, "observed network identity does not match manifest network")
        _require(str(provenance.get("observed_network_version", "")).lower() == NETWORK_VERSION[network],
                 "network marker does not match the selected network")
    for key in ("legacy_nis_version", "legacy_java_version", "legacy_h2_version", "legacy_flyway_version"):
        _require(isinstance(provenance.get(key), str) and bool(provenance[key].strip()), f"provenance.{key} must be recorded (use 'unknown' only with evidence)")
        if provenance[key].strip().lower() == "unknown":
            evidence = provenance.get("legacy_version_evidence", {})
            _require(isinstance(evidence, dict) and isinstance(evidence.get(key), str) and bool(evidence[key].strip()),
                     f"provenance.legacy_version_evidence.{key} is required when version is unknown")
    _require(isinstance(manifest.get("operator"), str) and bool(manifest["operator"].strip()), "operator is required")
    _require(isinstance(manifest.get("execution_environment"), str) and bool(manifest["execution_environment"].strip()),
             "execution_environment is required")
    _require("notes" not in manifest or isinstance(manifest.get("notes"), str), "notes must be a string when present")

    _require(not artifact_dir.is_symlink(), "artifact directory itself must not be a symlink")
    root = artifact_dir.resolve(strict=True)
    _require(root.is_dir(), "artifact directory must exist")
    _require(not _inside(root, REPO_ROOT), "source artifact must be outside the Git repository")
    recorded_root = Path(str(manifest.get("original_artifact_path", ""))).resolve(strict=False)
    _require(recorded_root == root, "original_artifact_path must match --artifact-dir")
    copy_path = Path(str(manifest.get("disposable_working_copy_path", ""))).resolve(strict=False)
    _require(bool(str(manifest.get("disposable_working_copy_path", "")).strip()), "disposable_working_copy_path is required")
    _require(not _inside(copy_path, REPO_ROOT), "disposable working copy must be outside the Git repository")
    _require(not _inside(copy_path, root) and not _inside(root, copy_path), "source and disposable copy must not overlap")

    files = manifest.get("database_files")
    _require(isinstance(files, list) and bool(files), "database_files must be a non-empty array")
    seen: set[str] = set()
    actual: list[dict[str, Any]] = []
    has_h2_file = False
    for item in files:
        _require(isinstance(item, dict), "each database_files entry must be an object")
        relative = item.get("path")
        _require(isinstance(relative, str) and bool(relative.strip()), "database file path is required")
        rel = Path(relative)
        _require(not rel.is_absolute() and ".." not in rel.parts, f"unsafe database file path: {relative}")
        normalized = rel.as_posix()
        _require(normalized not in seen, f"duplicate database file: {normalized}")
        seen.add(normalized)
        path = root / rel
        _require(path.is_file() and not path.is_symlink(), f"missing or symlink database file: {normalized}")
        _require(isinstance(item.get("size_bytes"), int) and item["size_bytes"] > 0, f"invalid size for {normalized}")
        _require(path.stat().st_size == item["size_bytes"], f"size mismatch for {normalized}")
        expected_sha = item.get("sha256")
        _require(_is_hash(expected_sha), f"invalid SHA-256 for {normalized}")
        actual_sha = _sha256(path)
        _require(actual_sha == expected_sha.lower(), f"SHA-256 mismatch for {normalized}")
        has_h2_file |= normalized.lower().endswith((".mv.db", ".h2.db", ".data.db"))
        actual.append({"path": normalized, "size_bytes": path.stat().st_size, "sha256": actual_sha})
    _require(has_h2_file, "database_files must include an H2 database file")

    listed = set(seen)
    found = set()
    for path in root.rglob("*"):
        _require(not path.is_symlink(), f"symlinks are not accepted in artifact: {path.relative_to(root)}")
        if path.is_file():
            found.add(path.relative_to(root).as_posix())
    _require(found == listed, "artifact directory contains unlisted files or manifest omits files")
    computed = directory_fingerprint(actual)
    recorded_fingerprint = manifest.get("directory_fingerprint_sha256")
    _require(_is_hash(recorded_fingerprint), "directory_fingerprint_sha256 must be a SHA-256 hash")
    _require(recorded_fingerprint.lower() == computed,
             "directory_fingerprint_sha256 mismatch")
    return {"network": network, "files": actual, "directory_fingerprint_sha256": computed,
            "original_artifact_path": str(root), "disposable_working_copy_path": str(copy_path)}


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as ex:
        raise ValidationError(f"cannot read manifest JSON: {ex}") from ex
    _require(isinstance(value, dict), "manifest root must be a JSON object")
    return value


def _load_json_file(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as ex:
        raise ValidationError(f"cannot read {label} JSON: {ex}") from ex
    _require(isinstance(value, dict), f"{label} root must be a JSON object")
    return value


def _trusted_file(base: Path, relative: Any, label: str) -> Path:
    _require(isinstance(relative, str) and bool(relative.strip()), f"{label} path is required")
    rel = Path(relative)
    _require(not rel.is_absolute() and ".." not in rel.parts, f"unsafe {label} path")
    candidate = base / rel
    current = base
    for part in rel.parts:
        current = current / part
        _require(not current.is_symlink(), f"{label} must not traverse symlinks")
    result = candidate.resolve(strict=True)
    _require(_inside(result, base.resolve(strict=True)), f"{label} must remain inside its evidence bundle")
    _require(result.is_file(), f"{label} must be a file")
    return result


def _public_key_fingerprint(public_key: Path) -> str:
    try:
        result = subprocess.run(
            ["openssl", "pkey", "-pubin", "-in", str(public_key), "-outform", "DER"],
            check=True, capture_output=True)
    except (OSError, subprocess.CalledProcessError) as ex:
        raise ValidationError(f"cannot read trusted Ed25519 public key: {ex}") from ex
    return hashlib.sha256(result.stdout).hexdigest()


def _verify_ed25519(public_key: Path, payload: Path, signature: Path, label: str) -> None:
    _require(signature.stat().st_size == 64, f"{label} Ed25519 signature must be exactly 64 bytes")
    try:
        subprocess.run(
            ["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(public_key),
             "-rawin", "-in", str(payload), "-sigfile", str(signature)],
            check=True, capture_output=True)
    except (OSError, subprocess.CalledProcessError) as ex:
        raise ValidationError(f"{label} Ed25519 signature verification failed") from ex


def _evidence_map(manifest: dict[str, Any], bundle_dir: Path) -> dict[str, tuple[dict[str, Any], Path]]:
    entries = manifest.get("evidence_files")
    _require(isinstance(entries, list) and bool(entries), "evidence_files must be a non-empty array")
    result: dict[str, tuple[dict[str, Any], Path]] = {}
    for item in entries:
        _require(isinstance(item, dict), "each evidence_files entry must be an object")
        evidence_id = item.get("id")
        _require(isinstance(evidence_id, str) and bool(evidence_id.strip()), "evidence file id is required")
        _require(evidence_id not in result, f"duplicate evidence file id: {evidence_id}")
        path = _trusted_file(bundle_dir, item.get("path"), f"evidence {evidence_id}")
        _require(isinstance(item.get("kind"), str) and bool(item["kind"].strip()),
                 f"evidence {evidence_id} kind is required")
        _require(isinstance(item.get("size_bytes"), int) and not isinstance(item["size_bytes"], bool)
                 and item["size_bytes"] > 0, f"invalid evidence size for {evidence_id}")
        _require(path.stat().st_size == item["size_bytes"], f"evidence size mismatch for {evidence_id}")
        _require(_is_hash(item.get("sha256")), f"invalid evidence SHA-256 for {evidence_id}")
        _require(_sha256(path) == item["sha256"].lower(), f"evidence SHA-256 mismatch for {evidence_id}")
        result[evidence_id] = (item, path)
    return result


def validate_evidence_manifest(
        manifest_path: Path,
        artifact_dir: Path,
        operator_public_key: Path,
        trusted_operator_id: str,
        checkpoint_public_key: Path,
        trusted_checkpoint_signer_id: str) -> dict[str, Any]:
    """Validate a v2 hash-bound, dual-signed artifact evidence bundle.

    Trust is anchored by public keys supplied out-of-band, not keys embedded in
    the submitted bundle. The operator signs the exact manifest bytes; an
    independently trusted checkpoint signer signs the checkpoint record bytes.
    """
    bundle_dir = manifest_path.resolve(strict=True).parent
    _require(not _inside(bundle_dir, REPO_ROOT), "evidence bundle must be outside the Git repository")
    _require(not manifest_path.is_symlink(), "manifest must not be a symlink")
    manifest_bytes = manifest_path.read_bytes()
    manifest = load_manifest(manifest_path)
    _require(manifest.get("manifest_version") == 2, "authenticated evidence requires manifest_version 2")

    authentication = manifest.get("authentication")
    _require(isinstance(authentication, dict), "authentication object is required")
    _require(authentication.get("scheme") == "ed25519-detached-manifest-v1",
             "unsupported or unauthenticated operator evidence")
    _require(authentication.get("signer_id") == trusted_operator_id,
             "operator signer identity does not match the externally pinned identity")
    expected_operator_key = _public_key_fingerprint(operator_public_key)
    _require(authentication.get("public_key_sha256") == expected_operator_key,
             "operator public key fingerprint does not match the externally pinned key")
    operator_signature = _trusted_file(bundle_dir, authentication.get("signature_path"), "operator signature")
    _require(operator_signature != manifest_path.resolve(strict=True),
             "operator signature must be detached from the manifest")
    # The detached signature covers the manifest exactly as delivered, including
    # the signature metadata and all artifact/evidence hashes.
    try:
        subprocess.run(
            ["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(operator_public_key),
             "-rawin", "-in", str(manifest_path), "-sigfile", str(operator_signature)],
            check=True, capture_output=True)
    except (OSError, subprocess.CalledProcessError) as ex:
        raise ValidationError("operator Ed25519 manifest signature verification failed") from ex

    _require(isinstance(trusted_checkpoint_signer_id, str) and bool(trusted_checkpoint_signer_id.strip()),
             "independently pinned checkpoint signer identity is required")
    _require(trusted_checkpoint_signer_id != trusted_operator_id,
             "operator and checkpoint signer must be distinct trust identities")
    checkpoint_key_fingerprint = _public_key_fingerprint(checkpoint_public_key)
    evidence = _evidence_map(manifest, bundle_dir)

    # Reuse the existing v1 structural/file inventory validation, but v1 alone
    # is never an authenticated trust decision.
    structural_manifest = dict(manifest)
    structural_manifest["manifest_version"] = 1
    artifact_inventory_before = inventory_artifact(artifact_dir)
    structural = validate_manifest(structural_manifest, artifact_dir, require_identity=True)
    artifact_inventory_after = inventory_artifact(artifact_dir)
    _require(artifact_inventory_before == artifact_inventory_after,
             "original artifact changed while evidence was being evaluated")
    _require(isinstance(manifest.get("artifact_id"), str), "artifact_id is required")
    _require(any(item["path"] == manifest["artifact_id"] and
                 item["path"].lower().endswith((".mv.db", ".h2.db", ".data.db"))
                 for item in structural["files"]),
             "artifact_id must identify a listed primary H2 database file")
    source = manifest.get("source")
    _require(isinstance(source, dict), "source object is required")
    for key in ("organization", "operator_id", "system_id", "node_id", "database_path", "nis_version", "h2_version"):
        _require(isinstance(source.get(key), str) and bool(source[key].strip()), f"source.{key} is required")
    _require(source["operator_id"] == trusted_operator_id,
             "source operator identity does not match authenticated manifest signer")
    _require(source.get("network") == manifest["network"], "source network does not match manifest network")

    provenance = manifest["provenance"]
    acquisition = manifest.get("acquisition")
    _require(isinstance(acquisition, dict), "acquisition object is required")
    for key in ("method", "acquisition_timestamp", "snapshot_timestamp", "db_state_at_acquisition"):
        _require(key in acquisition, f"acquisition.{key} is required")
    _require(isinstance(acquisition["method"], str) and bool(acquisition["method"].strip()),
             "acquisition.method is required")
    _timestamp(acquisition["acquisition_timestamp"], "acquisition.acquisition_timestamp")
    _timestamp(acquisition["snapshot_timestamp"], "acquisition.snapshot_timestamp")
    acquired_at = datetime.fromisoformat(acquisition["acquisition_timestamp"].replace("Z", "+00:00"))
    snapshot_at = datetime.fromisoformat(acquisition["snapshot_timestamp"].replace("Z", "+00:00"))
    _require(acquired_at >= snapshot_at, "acquisition timestamp precedes snapshot timestamp")
    _require(acquisition["snapshot_timestamp"] == provenance["snapshot_date"],
             "acquisition snapshot timestamp does not match provenance snapshot_date")
    db_state = acquisition["db_state_at_acquisition"]
    _require(isinstance(db_state, dict), "acquisition.db_state_at_acquisition must be an object")
    acquisition_entry = evidence.get("acquisition_record")
    _require(acquisition_entry is not None and acquisition_entry[0].get("kind") == "acquisition_record",
             "authenticated acquisition_record evidence is required")
    acquisition_record = _load_json_file(acquisition_entry[1], "acquisition_record")
    _require(acquisition_record.get("network") == manifest["network"]
             and acquisition_record.get("source_system_id") == source["system_id"]
             and acquisition_record.get("source_database_path") == source["database_path"]
             and acquisition_record.get("acquired_at") == acquisition["acquisition_timestamp"],
             "acquisition record does not match signed source/acquisition fields")
    source_files = acquisition_record.get("source_files")
    _require(isinstance(source_files, list) and all(isinstance(row, dict) for row in source_files),
             "acquisition record source_files must be an array of file records")
    recorded_files = sorted(source_files, key=lambda row: str(row.get("path", "")))
    manifest_files = sorted(manifest["database_files"], key=lambda row: row.get("path", ""))
    _require(recorded_files == manifest_files, "source acquisition hashes/sizes do not match candidate artifact files")
    received_files = acquisition_record.get("received_files")
    _require(isinstance(received_files, list) and all(isinstance(row, dict) for row in received_files),
             "acquisition record received_files must include post-transfer hashes and sizes")
    _require(sorted(received_files, key=lambda row: str(row.get("path", ""))) == manifest_files,
             "post-transfer hashes/sizes do not match the candidate artifact")
    _require(acquisition.get("observed_chain_height") == provenance["snapshot_height"]
             and _is_hash(acquisition.get("observed_tip_block_hash"))
             and acquisition["observed_tip_block_hash"].lower() == provenance["snapshot_block_hash"].lower()
             and acquisition_record.get("observed_chain_height") == acquisition["observed_chain_height"]
             and acquisition_record.get("observed_tip_block_hash") == acquisition["observed_tip_block_hash"],
             "acquisition-time chain checkpoint does not match the signed snapshot height/tip")

    source_entry = evidence.get("source_identity")
    _require(source_entry is not None and source_entry[0].get("kind") == "source_identity_record",
             "authenticated source_identity_record evidence is required")
    identity_record = _load_json_file(source_entry[1], "source_identity_record")
    for field in ("organization", "operator_id", "system_id", "node_id", "network", "nis_version", "h2_version"):
        expected = source["network"] if field == "network" else source[field]
        _require(identity_record.get(field) == expected, f"source identity evidence mismatch: {field}")

    fingerprint_entry = evidence.get("database_fingerprint")
    _require(fingerprint_entry is not None and fingerprint_entry[0].get("kind") == "database_fingerprint",
             "read-only database_fingerprint evidence is required")
    fingerprint = _load_json_file(fingerprint_entry[1], "database_fingerprint")
    fingerprint_genesis = fingerprint.get("genesis_block_hash")
    fingerprint_tip = fingerprint.get("tip_block_hash")
    _require(fingerprint.get("network") == manifest["network"]
             and str(fingerprint.get("network_version", "")).lower() == NETWORK_VERSION[manifest["network"]]
             and _is_hash(fingerprint_genesis)
             and fingerprint_genesis.lower() == provenance["observed_genesis_block_hash"].lower()
             and fingerprint.get("height") == provenance["observed_chain_height"]
             and _is_hash(fingerprint_tip)
             and fingerprint_tip.lower() == provenance["observed_tip_block_hash"].lower(),
             "database fingerprint does not match signed network/genesis/height/tip claims")

    quiescence = manifest.get("quiescence")
    _require(isinstance(quiescence, dict), "structured quiescence object is required")
    method = quiescence.get("method")
    if method == "normal-nis-shutdown-h2-close":
        _require(acquisition["method"] == "copy-after-normal-shutdown",
                 "normal shutdown evidence requires copy-after-normal-shutdown acquisition method")
        _require(db_state.get("nis_process") == "stopped" and db_state.get("h2_writer") == "closed",
                 "normal shutdown requires stopped NIS and closed H2 writer states")
        shutdown_entry, close_entry = evidence.get("nis_shutdown"), evidence.get("h2_close")
        _require(shutdown_entry is not None and shutdown_entry[0].get("kind") == "nis_shutdown_record",
                 "NIS shutdown evidence is required")
        _require(close_entry is not None and close_entry[0].get("kind") == "h2_close_record",
                 "H2 close evidence is required")
        shutdown = _load_json_file(shutdown_entry[1], "NIS shutdown")
        close = _load_json_file(close_entry[1], "H2 close")
        _timestamp(quiescence.get("nis_shutdown_completed_at"), "quiescence.nis_shutdown_completed_at")
        _timestamp(quiescence.get("h2_close_completed_at"), "quiescence.h2_close_completed_at")
        nis_closed = datetime.fromisoformat(quiescence["nis_shutdown_completed_at"].replace("Z", "+00:00"))
        h2_closed = datetime.fromisoformat(quiescence["h2_close_completed_at"].replace("Z", "+00:00"))
        _require(nis_closed <= snapshot_at and h2_closed <= snapshot_at,
                 "NIS/H2 shutdown must complete no later than snapshot time")
        _require(shutdown.get("event") == "nis_shutdown_completed" and shutdown.get("exit_code") == 0
                 and shutdown.get("source_system_id") == source["system_id"]
                 and shutdown.get("network") == manifest["network"]
                 and shutdown.get("completed_at") == quiescence["nis_shutdown_completed_at"],
                 "NIS shutdown evidence is incomplete or inconsistent")
        _require(close.get("event") == "h2_close_completed" and close.get("database_closed") is True
                 and close.get("source_system_id") == source["system_id"]
                 and close.get("database_path") == source["database_path"]
                 and close.get("completed_at") == quiescence["h2_close_completed_at"],
                 "H2 close evidence is incomplete or inconsistent")
    elif method == "application-consistent-storage-snapshot":
        _require(acquisition["method"] == "application-consistent-storage-snapshot",
                 "storage snapshot evidence requires matching acquisition method")
        _require(db_state.get("h2_writer") == "frozen" and db_state.get("snapshot_consistency") == "application-consistent",
                 "storage snapshot requires an application-consistent frozen-writer state")
        storage_entry, writer_entry = evidence.get("storage_snapshot"), evidence.get("db_writer_frozen")
        _require(storage_entry is not None and storage_entry[0].get("kind") == "storage_snapshot_record",
                 "storage snapshot evidence is required")
        _require(writer_entry is not None and writer_entry[0].get("kind") == "db_writer_frozen_record",
                 "database writer freeze evidence is required")
        storage = _load_json_file(storage_entry[1], "storage snapshot")
        writer = _load_json_file(writer_entry[1], "database writer freeze")
        _timestamp(quiescence.get("writer_frozen_at"), "quiescence.writer_frozen_at")
        writer_frozen_at = datetime.fromisoformat(quiescence["writer_frozen_at"].replace("Z", "+00:00"))
        _require(writer_frozen_at <= snapshot_at, "database writer must be frozen before snapshot completion")
        _require(storage.get("event") == "application_consistent_snapshot_completed"
                 and storage.get("source_system_id") == source["system_id"]
                 and storage.get("completed_at") == acquisition["snapshot_timestamp"]
                 and storage.get("atomic") is True and storage.get("application_consistent") is True,
                 "storage snapshot evidence is incomplete or inconsistent")
        _require(writer.get("event") == "database_writer_frozen"
                 and writer.get("source_system_id") == source["system_id"]
                 and writer.get("frozen_at") == quiescence["writer_frozen_at"],
                 "database writer freeze evidence is incomplete or inconsistent")
    else:
        raise ValidationError("quiescence.method must be a recognized normal-shutdown or application-consistent snapshot")

    checkpoint = manifest.get("independent_checkpoint")
    _require(isinstance(checkpoint, dict), "independent_checkpoint object is required")
    for key in ("source_id", "source_operator_id", "source_url", "network", "height", "block_hash",
                "genesis_block_hash",
                "retrieved_at", "evidence_id", "signature_path", "signer_id", "public_key_sha256"):
        _require(key in checkpoint, f"independent_checkpoint.{key} is required")
    _timestamp(checkpoint["retrieved_at"], "independent_checkpoint.retrieved_at")
    _require(_is_hash(checkpoint.get("block_hash")), "independent_checkpoint.block_hash must be a 32-byte hex hash")
    _require(checkpoint["source_operator_id"] != source["operator_id"]
             and checkpoint["source_id"] != source["system_id"],
             "checkpoint must come from a distinct independent source/operator")
    _require(checkpoint["network"] == manifest["network"]
             and checkpoint["height"] == provenance["snapshot_height"]
             and checkpoint["genesis_block_hash"].lower() == provenance["expected_genesis_block_hash"].lower()
             and isinstance(checkpoint["block_hash"], str)
             and checkpoint["block_hash"].lower() == provenance["snapshot_block_hash"].lower(),
             "independent checkpoint does not match the candidate network/height/tip")
    checkpoint_entry = evidence.get(checkpoint["evidence_id"])
    _require(checkpoint_entry is not None and checkpoint_entry[0].get("kind") == "signed_checkpoint_record",
             "signed checkpoint record evidence is required")
    checkpoint_record = _load_json_file(checkpoint_entry[1], "signed checkpoint record")
    for field, expected in (("source_id", checkpoint["source_id"]), ("source_operator_id", checkpoint["source_operator_id"]),
                            ("source_url", checkpoint["source_url"]), ("network", checkpoint["network"]),
                            ("height", checkpoint["height"]), ("block_hash", checkpoint["block_hash"]),
                            ("genesis_block_hash", checkpoint["genesis_block_hash"]),
                            ("retrieved_at", checkpoint["retrieved_at"])):
        _require(checkpoint_record.get(field) == expected, f"signed checkpoint record mismatch: {field}")
    _require(checkpoint["signer_id"] == trusted_checkpoint_signer_id,
             "checkpoint signer identity does not match independently pinned identity")
    _require(checkpoint["signer_id"] != trusted_operator_id,
             "checkpoint signer must be independent of the artifact source operator")
    _require(checkpoint["public_key_sha256"] == checkpoint_key_fingerprint,
             "checkpoint signer key fingerprint does not match the independently pinned key")
    checkpoint_signature = _trusted_file(bundle_dir, checkpoint["signature_path"], "checkpoint signature")
    _require(checkpoint_signature != checkpoint_entry[1], "checkpoint signature must be detached from its record")
    _verify_ed25519(checkpoint_public_key, checkpoint_entry[1], checkpoint_signature, "checkpoint")

    return {
        "network": manifest["network"],
        "artifact_id": manifest["artifact_id"],
        "artifact_files": structural["files"],
        "directory_fingerprint_sha256": structural["directory_fingerprint_sha256"],
        "original_artifact_immutable_during_evaluation": True,
        "operator_signer_id": trusted_operator_id,
        "operator_public_key_sha256": expected_operator_key,
        "checkpoint_signer_id": trusted_checkpoint_signer_id,
        "checkpoint_public_key_sha256": checkpoint_key_fingerprint,
        "provenance": "pass",
        "quiescence": "pass",
        "independent_checkpoint": "pass",
        "decision": "EVIDENCE_CONTRACT_PASS_REQUIRES_PHASE2F_PAIR_GATE",
        "manifest_sha256": hashlib.sha256(manifest_bytes).hexdigest(),
    }


def validate_evidence_pair(mainnet_args: tuple[Any, ...], testnet_args: tuple[Any, ...]) -> dict[str, Any]:
    """Require independently authenticated Mainnet and Testnet evidence bundles."""
    mainnet = validate_evidence_manifest(*mainnet_args)
    testnet = validate_evidence_manifest(*testnet_args)
    _require(mainnet["network"] == "mainnet", "Mainnet evidence input does not identify Mainnet")
    _require(testnet["network"] == "testnet", "Testnet evidence input does not identify Testnet")
    _require(mainnet["artifact_files"] != testnet["artifact_files"],
             "Mainnet and Testnet must be distinct artifacts")
    return {
        "mainnet": mainnet,
        "testnet": testnet,
        "decision": "PROVENANCE_QUIESCENCE_EVIDENCE_PASS_BOTH_NETWORKS",
        "overall_phase2f_gate": "REQUIRES_PRIOR_RUNTIME_AND_CHAIN_STATE_ACCEPTANCE",
    }


def prepare_copy(manifest: dict[str, Any], artifact_dir: Path, destination: Path) -> dict[str, Any]:
    # This stage validates physical provenance and file integrity only. Full
    # identity acceptance must happen after inspecting the disposable copy.
    verified = validate_manifest(manifest, artifact_dir, require_identity=False)
    dest = destination.resolve(strict=False)
    _require(not _inside(dest, REPO_ROOT), "disposable copy must be outside the Git repository")
    declared_dest = Path(str(manifest.get("disposable_working_copy_path", ""))).resolve(strict=False)
    _require(dest == declared_dest, "working copy destination must match manifest disposable_working_copy_path")
    _require(not dest.exists(), "disposable copy destination must not already exist")
    _require(not _inside(dest, artifact_dir.resolve()) and not _inside(artifact_dir.resolve(), dest),
             "working copy must not overlap the original artifact")
    dest.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    source_hash_before = verified["directory_fingerprint_sha256"]
    dest.mkdir(mode=0o700)
    try:
        for item in verified["files"]:
            relative = Path(item["path"])
            source = artifact_dir.resolve() / relative
            target = dest / relative
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            shutil.copystat(source, target, follow_symlinks=False)
            target.chmod(stat.S_IRUSR | stat.S_IWUSR)
        copy_items = []
        for item in verified["files"]:
            target = dest / item["path"]
            copy_items.append({"path": item["path"], "size_bytes": target.stat().st_size, "sha256": _sha256(target)})
        _require(directory_fingerprint(copy_items) == source_hash_before, "disposable copy verification failed")
        source_after = validate_manifest(manifest, artifact_dir, require_identity=False)
        _require(source_after["directory_fingerprint_sha256"] == source_hash_before,
                 "original artifact changed during copy")
    except Exception:
        shutil.rmtree(dest, ignore_errors=True)
        raise
    return {"network": verified["network"], "identity_verified": False,
            "original_fingerprint_before": source_hash_before,
            "original_fingerprint_after": source_after["directory_fingerprint_sha256"],
            "working_copy_fingerprint": directory_fingerprint(copy_items), "working_copy": str(dest),
            "files": copy_items}


def compare_fingerprints(before_path: Path, after_path: Path) -> dict[str, Any]:
    try:
        before = json.loads(before_path.read_text(encoding="utf-8"))
        after = json.loads(after_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as ex:
        raise ValidationError(f"cannot read fingerprint JSON: {ex}") from ex
    for label, value in (("before", before), ("after", after)):
        _require(isinstance(value, dict), f"{label} fingerprint must be a JSON object")
        missing = [key for key in FINGERPRINT_KEYS if key not in value]
        _require(not missing, f"{label} fingerprint missing fields: {', '.join(missing)}")
        _require(value["format"] == "nem-nis-chain-state-v1", f"unsupported {label} fingerprint format")
        _require(isinstance(value["network"], str) and value["network"] in NETWORK_VERSION,
                 f"invalid {label} network")
        _require(str(value["network_version"]).lower() == NETWORK_VERSION[value["network"]],
                 f"{label} network marker mismatch")
        _require(_is_hash(value["genesis_block_hash"]) and _is_hash(value["tip_block_hash"]),
                 f"{label} block hashes must be 32-byte hex")
        _require(isinstance(value["height"], int) and not isinstance(value["height"], bool)
                 and value["height"] >= 1, f"{label} height must be positive")
        _require(isinstance(value["counts"], dict) and bool(value["counts"]), f"{label} counts are required")
        _require(isinstance(value["schema"], list) and bool(value["schema"]), f"{label} schema inventory is required")
        _require(isinstance(value["tables"], list) and bool(value["tables"]), f"{label} table digests are required")
        names = [row.get("name") for row in value["tables"] if isinstance(row, dict)]
        _require(len(names) == len(value["tables"]) and len(names) == len(set(names)),
                 f"{label} table digest names must be unique")
        schema_names = [row.get("name") for row in value["schema"] if isinstance(row, dict)]
        _require(len(schema_names) == len(value["schema"]) and len(schema_names) == len(set(schema_names)),
                 f"{label} schema table names must be unique")
        for row in value["tables"]:
            _require(isinstance(row.get("row_count"), int) and row["row_count"] >= 0,
                     f"invalid {label} row count")
            _require(_is_hash(row.get("sha256")), f"invalid {label} table SHA-256")
    _require(before["network"] == after["network"], "network changed across conversion")
    before_normalized = dict(before)
    after_normalized = dict(after)
    before_normalized["tables"] = sorted(before["tables"], key=lambda row: row["name"])
    after_normalized["tables"] = sorted(after["tables"], key=lambda row: row["name"])
    before_normalized["schema"] = sorted(before["schema"], key=lambda row: row["name"])
    after_normalized["schema"] = sorted(after["schema"], key=lambda row: row["name"])
    differences = {key: {"before": before_normalized[key], "after": after_normalized[key]}
                   for key in FINGERPRINT_KEYS if before_normalized[key] != after_normalized[key]}
    return {"equivalent": not differences, "differences": differences}


def _git_output(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=REPO_ROOT, check=True, capture_output=True)
    return result.stdout.decode("utf-8").strip()


def _version_key(version: str) -> tuple[int, ...]:
    try:
        parts = version.split(".")
        _require(bool(parts) and all(part.isdigit() for part in parts), f"unsupported Flyway version format: {version}")
        return tuple(int(part) for part in parts)
    except ValueError as ex:
        raise ValidationError(f"unsupported Flyway version format: {version}") from ex


def _historical_migration_candidates(version: str, script_name: str, migration_dir: Path) -> list[dict[str, Any]]:
    migration_dir = migration_dir.resolve(strict=True)
    _require(_inside(migration_dir, REPO_ROOT), "migration directory must be inside this repository")
    current = migration_dir / script_name
    _require(current.is_file(), f"migration script is missing from repository: {script_name}")
    relative_dir = migration_dir.relative_to(REPO_ROOT).as_posix()
    relative_file = current.relative_to(REPO_ROOT).as_posix()
    commits = _git_output("log", "--all", "--follow", "--format=%H", "--", relative_file).splitlines()
    candidates: dict[tuple[str, str], dict[str, Any]] = {}
    for commit in commits:
        paths = _git_output("ls-tree", "-r", "--name-only", commit, "--", relative_dir).splitlines()
        matches = [path for path in paths if Path(path).name == script_name]
        if not matches:
            matches = [path for path in paths if Path(path).name.startswith(f"V{version}__") and path.endswith(".sql")]
        for path in matches:
            blob = _git_output("rev-parse", f"{commit}:{path}")
            key = (blob, Path(path).name)
            if key in candidates:
                continue
            raw = subprocess.run(["git", "show", f"{commit}:{path}"], cwd=REPO_ROOT, check=True, capture_output=True).stdout
            checksum = zlib.crc32(raw)
            if checksum >= 0x80000000:
                checksum -= 0x100000000
            candidates[key] = {
                "commit": commit,
                "blob": blob,
                "script": Path(path).name,
                "path": path,
                "bytes": len(raw),
                "sha256": hashlib.sha256(raw).hexdigest(),
                "flyway3_raw_crc32": checksum,
                "line_endings": "CRLF" if b"\r\n" in raw else ("CR" if b"\r" in raw else "LF"),
                "utf8_bom": raw.startswith(b"\xef\xbb\xbf"),
            }
    _require(bool(candidates), f"no Git migration revisions found for {script_name}")
    return sorted(candidates.values(), key=lambda row: (row["commit"], row["path"]))


def audit_flyway_history(fingerprint_path: Path, migration_dir: Path) -> dict[str, Any]:
    try:
        fingerprint = json.loads(fingerprint_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as ex:
        raise ValidationError(f"cannot read database fingerprint: {ex}") from ex
    _require(isinstance(fingerprint, dict), "database fingerprint must be a JSON object")
    history = fingerprint.get("flyway_history")
    _require(isinstance(history, dict) and isinstance(history.get("table"), str)
             and history["table"].lower() == "schema_version",
             "legacy schema_version history is required")
    rows = history.get("rows")
    _require(isinstance(rows, list) and bool(rows), "legacy Flyway history rows are required")
    expected_files: dict[str, Path] = {}
    for path in migration_dir.glob("V*__*.sql"):
        version = path.name[1:].split("__", 1)[0]
        _require(version not in expected_files, f"duplicate repository migration version: {version}")
        expected_files[version] = path
    _require(bool(expected_files), "no versioned migration scripts found")
    applied: dict[str, dict[str, Any]] = {}
    evidence: list[dict[str, Any]] = []
    installed_ranks: list[int] = []
    for raw_row in rows:
        _require(isinstance(raw_row, dict), "invalid Flyway history row")
        row = {str(key).lower(): value for key, value in raw_row.items()}
        version = row.get("version")
        script_name = row.get("script")
        _require(isinstance(version, str) and bool(version), "Flyway row without a version requires manual review")
        _require(version in expected_files, f"unknown database migration version: {version}")
        _require(version not in applied, f"duplicate database migration version: {version}")
        _require(str(row.get("success", "")).lower() == "true", f"migration {version} is not successful")
        _require(row.get("type") == "SQL", f"migration {version} has an unexpected type")
        _require(isinstance(script_name, str) and bool(script_name), f"migration {version} has no script name")
        try:
            installed_rank = int(row["installed_rank"])
        except (KeyError, TypeError, ValueError) as ex:
            raise ValidationError(f"migration {version} has no integer installed_rank") from ex
        _require(installed_rank > 0 and installed_rank not in installed_ranks,
                 f"migration {version} has invalid or duplicate installed_rank")
        installed_ranks.append(installed_rank)
        expected_description = script_name.split("__", 1)[-1].removesuffix(".sql").replace("_", " ")
        _require(row.get("description") == expected_description,
                 f"migration {version} description does not match its script name")
        try:
            stored_checksum = int(row["checksum"])
        except (KeyError, TypeError, ValueError) as ex:
            raise ValidationError(f"migration {version} has no integer checksum") from ex
        candidates = _historical_migration_candidates(version, script_name, migration_dir)
        matching = [candidate for candidate in candidates
                    if candidate["script"] == script_name and candidate["flyway3_raw_crc32"] == stored_checksum]
        _require(bool(matching), f"migration {version} checksum has no matching audited Git blob")
        applied[version] = row
        evidence.append({"version": version, "script": script_name, "stored_checksum": stored_checksum,
                         "matching_git_blobs": matching, "candidate_count": len(candidates)})

    ordered_applied = sorted(applied, key=_version_key)
    _require(installed_ranks == sorted(installed_ranks), "Flyway installed_rank ordering is inconsistent")
    max_applied = max(ordered_applied, key=_version_key)
    expected_through_max = sorted((version for version in expected_files if _version_key(version) <= _version_key(max_applied)),
                                  key=_version_key)
    _require(ordered_applied == expected_through_max,
             "legacy history has a migration gap before its latest applied version")
    pending = sorted((version for version in expected_files if _version_key(version) > _version_key(max_applied)),
                     key=_version_key)
    return {"accepted": True, "history_table": "schema_version", "latest_applied_version": max_applied,
            "applied_versions": ordered_applied, "pending_repository_versions": pending,
            "migration_evidence": evidence,
            "note": "This proves the stored CRC32 matches an audited Git script blob; it does not prove DB content was actually produced by that script."}


def _report_target(path: Path | None, protected: tuple[Path, ...] = ()) -> Path | None:
    if path is None:
        return None
    target = path.resolve(strict=False)
    _require(not _inside(target, REPO_ROOT), "evidence report must be written outside the Git repository")
    for protected_path in protected:
        protected_root = protected_path.resolve(strict=False)
        _require(not _inside(target, protected_root),
                 f"evidence report must not be written inside protected path: {protected_root}")
    target.parent.mkdir(parents=True, exist_ok=True)
    return target


def _write_report(path: Path | None, value: dict[str, Any], protected: tuple[Path, ...] = ()) -> None:
    rendered = json.dumps(value, indent=2, sort_keys=True) + "\n"
    target = _report_target(path, protected)
    if target is None:
        print(rendered, end="")
        return
    target.write_text(rendered, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    inventory = subparsers.add_parser("inventory")
    inventory.add_argument("artifact_dir", type=Path)
    inventory.add_argument("--report", type=Path)
    for name in ("validate-manifest", "verify-original", "prepare-copy"):
        sub = subparsers.add_parser(name)
        sub.add_argument("manifest", type=Path)
        sub.add_argument("artifact_dir", type=Path)
        sub.add_argument("--report", type=Path)
        if name == "prepare-copy":
            sub.add_argument("working_copy", type=Path)
    compare = subparsers.add_parser("compare-fingerprints")
    compare.add_argument("before", type=Path)
    compare.add_argument("after", type=Path)
    compare.add_argument("--report", type=Path)
    flyway = subparsers.add_parser("audit-flyway-history")
    flyway.add_argument("fingerprint", type=Path)
    flyway.add_argument("--migration-dir", type=Path, default=REPO_ROOT / "nis/src/main/resources/db/h2")
    flyway.add_argument("--report", type=Path)
    evidence = subparsers.add_parser("evaluate-evidence",
                                    help="verify a v2 evidence bundle against out-of-band trusted Ed25519 keys")
    evidence.add_argument("manifest", type=Path)
    evidence.add_argument("artifact_dir", type=Path)
    evidence.add_argument("--operator-key", type=Path, required=True)
    evidence.add_argument("--operator-id", required=True)
    evidence.add_argument("--checkpoint-key", type=Path, required=True)
    evidence.add_argument("--checkpoint-signer-id", required=True)
    evidence.add_argument("--report", type=Path)
    pair = subparsers.add_parser("evaluate-evidence-pair",
                                help="require signed provenance/quiescence evidence for both networks")
    for network in ("mainnet", "testnet"):
        pair.add_argument(f"--{network}-manifest", type=Path, required=True)
        pair.add_argument(f"--{network}-artifact-dir", type=Path, required=True)
        pair.add_argument(f"--{network}-operator-key", type=Path, required=True)
        pair.add_argument(f"--{network}-operator-id", required=True)
        pair.add_argument(f"--{network}-checkpoint-key", type=Path, required=True)
        pair.add_argument(f"--{network}-checkpoint-signer-id", required=True)
    pair.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "inventory":
            _report_target(args.report, (args.artifact_dir,))
            _write_report(args.report, inventory_artifact(args.artifact_dir), (args.artifact_dir,))
            return 0
        if args.command == "audit-flyway-history":
            _report_target(args.report, (args.fingerprint,))
            result = audit_flyway_history(args.fingerprint, args.migration_dir)
            _write_report(args.report, result, (args.fingerprint,))
            return 0
        if args.command == "compare-fingerprints":
            _report_target(args.report, (args.before, args.after))
            result = compare_fingerprints(args.before, args.after)
            _write_report(args.report, result, (args.before, args.after))
            return 0 if result["equivalent"] else 1
        if args.command == "evaluate-evidence":
            protected = (args.artifact_dir, args.manifest.parent)
            _report_target(args.report, protected)
            result = validate_evidence_manifest(
                args.manifest, args.artifact_dir, args.operator_key, args.operator_id,
                args.checkpoint_key, args.checkpoint_signer_id)
            _write_report(args.report, result, protected)
            return 0
        if args.command == "evaluate-evidence-pair":
            protected = (args.mainnet_artifact_dir, args.testnet_artifact_dir,
                         args.mainnet_manifest.parent, args.testnet_manifest.parent)
            _report_target(args.report, protected)
            mainnet_args = (args.mainnet_manifest, args.mainnet_artifact_dir, args.mainnet_operator_key,
                            args.mainnet_operator_id, args.mainnet_checkpoint_key,
                            args.mainnet_checkpoint_signer_id)
            testnet_args = (args.testnet_manifest, args.testnet_artifact_dir, args.testnet_operator_key,
                            args.testnet_operator_id, args.testnet_checkpoint_key,
                            args.testnet_checkpoint_signer_id)
            result = validate_evidence_pair(mainnet_args, testnet_args)
            _write_report(args.report, result, protected)
            return 0
        manifest = load_manifest(args.manifest)
        if args.command == "prepare-copy":
            _report_target(args.report, (args.artifact_dir, args.working_copy))
            result = prepare_copy(manifest, args.artifact_dir, args.working_copy)
            protected = (args.artifact_dir, args.working_copy)
        else:
            _report_target(args.report, (args.artifact_dir, Path(manifest["disposable_working_copy_path"])))
            result = validate_manifest(manifest, args.artifact_dir)
            result["accepted_for_intake_only"] = True
            result["trust_decision"] = "NOT_AUTHENTICATED_V1_MANIFEST"
            protected = (args.artifact_dir, Path(manifest["disposable_working_copy_path"]))
        _write_report(args.report, result, protected)
        return 0
    except (ValidationError, OSError) as ex:
        print(f"REJECTED: {ex}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
