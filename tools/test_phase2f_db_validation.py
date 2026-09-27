import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import zlib

import phase2f_db_validation as validation


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class ArtifactValidationTest(unittest.TestCase):
	def setUp(self):
		self.temp = tempfile.TemporaryDirectory()
		self.root = Path(self.temp.name)
		self.artifact = self.root / "artifact"
		self.artifact.mkdir()
		self.data = b"disposable test-only bytes"
		(self.artifact / "nis.mv.db").write_bytes(self.data)
		file_entry = {"path": "nis.mv.db", "size_bytes": len(self.data),
				"sha256": hashlib.sha256(self.data).hexdigest()}
		self.manifest = {
			"manifest_version": 1,
			"network": "mainnet",
			"classification": "production",
			"complete_file_inventory": True,
			"original_artifact_path": str(self.artifact),
			"disposable_working_copy_path": str(self.root / "work"),
			"operator": "test operator",
			"execution_environment": "unit test",
			"database_files": [file_entry],
			"directory_fingerprint_sha256": validation.directory_fingerprint([file_entry]),
			"provenance": {
				"source": "test-only fixture; never network evidence",
				"acquisition_date": "2026-09-27T00:00:00Z",
				"snapshot_date": "2026-09-26T00:00:00Z",
				"snapshot_height": 123,
				"observed_chain_height": 123,
				"snapshot_block_hash": _hash("tip"),
				"observed_tip_block_hash": _hash("tip"),
				"expected_genesis_block_hash": _hash("genesis"),
				"observed_genesis_block_hash": _hash("genesis"),
				"expected_genesis_evidence": "trusted owner checkpoint",
				"observed_genesis_evidence": "database inspection evidence",
				"observed_network": "mainnet",
				"observed_network_version": "0x68",
				"legacy_nis_version": "unknown",
				"legacy_java_version": "unknown",
				"legacy_h2_version": "1.4.200",
				"legacy_flyway_version": "3.2.1",
				"legacy_version_evidence": {
					"legacy_nis_version": "NIS build marker absent in provided fixture",
					"legacy_java_version": "runtime metadata unavailable for test fixture"},
				"quiesced": True,
				"quiesced_evidence": "operator shutdown record",
			},
		}

	def tearDown(self):
		self.temp.cleanup()

	def test_valid_manifest_and_complete_hash_inventory_are_accepted(self):
		result = validation.validate_manifest(self.manifest, self.artifact)
		self.assertEqual("mainnet", result["network"])
		self.assertEqual(hashlib.sha256(self.data).hexdigest(), result["files"][0]["sha256"])

	def test_inventory_has_deterministic_path_order_and_directory_hash(self):
		(self.artifact / "secondary.mv.db").write_bytes(b"second")
		result = validation.inventory_artifact(self.artifact)
		self.assertEqual(["nis.mv.db", "secondary.mv.db"], [item["path"] for item in result["files"]])
		self.assertEqual(validation.directory_fingerprint(result["files"]), result["directory_fingerprint_sha256"])

	def test_network_marker_mismatch_is_rejected(self):
		self.manifest["provenance"]["observed_network"] = "testnet"
		with self.assertRaisesRegex(validation.ValidationError, "network identity"):
			validation.validate_manifest(self.manifest, self.artifact)

	def test_genesis_mismatch_is_rejected(self):
		self.manifest["provenance"]["observed_genesis_block_hash"] = _hash("other genesis")
		with self.assertRaisesRegex(validation.ValidationError, "genesis block hash"):
			validation.validate_manifest(self.manifest, self.artifact)

	def test_missing_provenance_is_rejected(self):
		del self.manifest["provenance"]["source"]
		with self.assertRaisesRegex(validation.ValidationError, "provenance.source"):
			validation.validate_manifest(self.manifest, self.artifact)

	def test_unknown_legacy_version_requires_evidence(self):
		self.manifest["provenance"]["legacy_flyway_version"] = "unknown"
		with self.assertRaisesRegex(validation.ValidationError, "legacy_version_evidence.legacy_flyway_version"):
			validation.validate_manifest(self.manifest, self.artifact)

	def test_snapshot_hash_height_disagreement_is_rejected(self):
		self.manifest["provenance"]["observed_chain_height"] = 122
		with self.assertRaisesRegex(validation.ValidationError, "height"):
			validation.validate_manifest(self.manifest, self.artifact)

	def test_non_string_network_and_boolean_height_are_rejected(self):
		self.manifest["network"] = []
		with self.assertRaisesRegex(validation.ValidationError, "network must be"):
			validation.validate_manifest(self.manifest, self.artifact)
		self.manifest["network"] = "mainnet"
		self.manifest["provenance"]["snapshot_height"] = True
		with self.assertRaisesRegex(validation.ValidationError, "positive integer"):
			validation.validate_manifest(self.manifest, self.artifact)

	def test_synthetic_fixture_classification_is_rejected(self):
		self.manifest["classification"] = "synthetic"
		with self.assertRaisesRegex(validation.ValidationError, "synthetic/non-production"):
			validation.validate_manifest(self.manifest, self.artifact)

	def test_file_hash_mismatch_is_rejected(self):
		(self.artifact / "nis.mv.db").write_bytes(b"tampered")
		with self.assertRaisesRegex(validation.ValidationError, "size mismatch|SHA-256 mismatch"):
			validation.validate_manifest(self.manifest, self.artifact)

	def test_unlisted_sidecar_is_rejected(self):
		(self.artifact / "nis.trace.db").write_bytes(b"sidecar")
		with self.assertRaisesRegex(validation.ValidationError, "unlisted files"):
			validation.validate_manifest(self.manifest, self.artifact)

	def test_copy_is_disposable_and_original_hashes_remain_unchanged(self):
		original_hash = hashlib.sha256((self.artifact / "nis.mv.db").read_bytes()).hexdigest()
		destination = self.root / "work"
		result = validation.prepare_copy(self.manifest, self.artifact, destination)
		self.assertEqual(result["original_fingerprint_before"], result["original_fingerprint_after"])
		self.assertEqual(original_hash, hashlib.sha256((self.artifact / "nis.mv.db").read_bytes()).hexdigest())
		self.assertEqual(self.data, (destination / "nis.mv.db").read_bytes())
		self.assertTrue((destination / "nis.mv.db").stat().st_mode & 0o200)

	def test_copy_destination_must_match_manifest_and_not_overlap_source(self):
		with self.assertRaisesRegex(validation.ValidationError, "must match manifest"):
			validation.prepare_copy(self.manifest, self.artifact, self.root / "other")
		self.manifest["disposable_working_copy_path"] = str(self.artifact / "nested")
		with self.assertRaisesRegex(validation.ValidationError, "must not overlap"):
			validation.prepare_copy(self.manifest, self.artifact, self.artifact / "nested")

	def test_staging_can_precede_database_identity_but_does_not_accept_it(self):
		for key in ("observed_chain_height", "observed_tip_block_hash", "observed_genesis_block_hash",
				"observed_genesis_evidence", "observed_network", "observed_network_version"):
			self.manifest["provenance"].pop(key, None)
		destination = self.root / "work"
		result = validation.prepare_copy(self.manifest, self.artifact, destination)
		self.assertFalse(result["identity_verified"])
		with self.assertRaisesRegex(validation.ValidationError, "observed"):
			validation.validate_manifest(self.manifest, self.artifact)

	def test_fingerprint_comparison_is_deterministic_and_detects_change(self):
		table = {"name": "blocks", "row_count": 3, "sha256": _hash("rows")}
		fingerprint = {"format": "nem-nis-chain-state-v1", "network": "mainnet", "network_version": "0x68",
				"genesis_block_hash": _hash("genesis"), "height": 3, "tip_block_hash": _hash("tip"),
				"counts": {"blocks": 3, "transactions": 4, "accounts": 2},
				"schema": [{"name": "blocks", "columns": [{"name": "id", "type": "BIGINT"}], "primary_key": ["id"]}],
				"tables": [table]}
		left = self.root / "before.json"
		right = self.root / "after.json"
		left.write_text(json.dumps(fingerprint), encoding="utf-8")
		right.write_text(json.dumps(fingerprint), encoding="utf-8")
		self.assertTrue(validation.compare_fingerprints(left, right)["equivalent"])
		fingerprint["tables"] = [{"name": "accounts", "row_count": 2, "sha256": _hash("accounts")}, table]
		left.write_text(json.dumps(fingerprint), encoding="utf-8")
		fingerprint["tables"] = list(reversed(fingerprint["tables"]))
		right.write_text(json.dumps(fingerprint), encoding="utf-8")
		self.assertTrue(validation.compare_fingerprints(left, right)["equivalent"])
		fingerprint["tip_block_hash"] = _hash("different tip")
		right.write_text(json.dumps(fingerprint), encoding="utf-8")
		self.assertFalse(validation.compare_fingerprints(left, right)["equivalent"])


class FlywayHistoryAuditTest(unittest.TestCase):
	def setUp(self):
		self.temp = tempfile.TemporaryDirectory()
		self.report_path = Path(self.temp.name) / "fingerprint.json"
		self.migration_dir = validation.REPO_ROOT / "nis/src/main/resources/db/h2"
		self.rows = []
		for path in sorted(self.migration_dir.glob("V*__*.sql")):
			version = path.name[1:].split("__", 1)[0]
			checksum = zlib.crc32(path.read_bytes())
			if checksum >= 0x80000000:
				checksum -= 0x100000000
			self.rows.append({"installed_rank": str(len(self.rows) + 1), "version": version,
					"description": path.name.split("__", 1)[1].removesuffix(".sql").replace("_", " "),
					"type": "SQL", "script": path.name,
					"checksum": str(checksum), "success": "true"})
		self._write_report()

	def tearDown(self):
		self.temp.cleanup()

	def _write_report(self):
		self.report_path.write_text(json.dumps({"flyway_history": {"table": "schema_version", "rows": self.rows}}),
				encoding="utf-8")

	def test_all_legacy_rows_must_match_an_audited_git_blob(self):
		result = validation.audit_flyway_history(self.report_path, self.migration_dir)
		self.assertTrue(result["accepted"])
		self.assertEqual("1.0.7", result["latest_applied_version"])
		self.assertEqual([], result["pending_repository_versions"])
		self.assertTrue(all(row["matching_git_blobs"] for row in result["migration_evidence"]))

	def test_unknown_version_is_rejected(self):
		self.rows[-1]["version"] = "9.9.9"
		self._write_report()
		with self.assertRaisesRegex(validation.ValidationError, "unknown database migration version"):
			validation.audit_flyway_history(self.report_path, self.migration_dir)

	def test_history_gap_is_rejected(self):
		self.rows = [row for row in self.rows if row["version"] != "1.0.6"]
		self._write_report()
		with self.assertRaisesRegex(validation.ValidationError, "gap"):
			validation.audit_flyway_history(self.report_path, self.migration_dir)

	def test_checksum_mismatch_is_rejected(self):
		self.rows[0]["checksum"] = str(int(self.rows[0]["checksum"]) + 1)
		self._write_report()
		with self.assertRaisesRegex(validation.ValidationError, "no matching audited Git blob"):
			validation.audit_flyway_history(self.report_path, self.migration_dir)

	def test_failed_migration_is_rejected(self):
		self.rows[0]["success"] = "false"
		self._write_report()
		with self.assertRaisesRegex(validation.ValidationError, "not successful"):
			validation.audit_flyway_history(self.report_path, self.migration_dir)

	def test_report_cannot_overwrite_artifact_or_fingerprint(self):
		with self.assertRaisesRegex(validation.ValidationError, "inside protected path"):
			validation._write_report(self.report_path, {"ok": True}, (self.report_path.parent,))


if __name__ == "__main__":
	unittest.main()
