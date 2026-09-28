import hashlib
import json
import subprocess
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


class EvidenceContractTest(unittest.TestCase):
	def setUp(self):
		self.temp = tempfile.TemporaryDirectory()
		self.root = Path(self.temp.name)
		self.bundle = self.root / "evidence"
		self.bundle.mkdir()
		self.artifact = self.root / "artifact"
		self.artifact.mkdir()
		self.payload = b"test-only database bytes; never network evidence"
		(self.artifact / "nis.mv.db").write_bytes(self.payload)
		self.operator_private, self.operator_public = self._make_key("operator")
		self.checkpoint_private, self.checkpoint_public = self._make_key("checkpoint")
		self.file_entry = {"path": "nis.mv.db", "size_bytes": len(self.payload),
				"sha256": hashlib.sha256(self.payload).hexdigest()}
		self.manifest = {
			"manifest_version": 2,
			"network": "mainnet",
			"classification": "production",
			"artifact_id": "nis.mv.db",
			"complete_file_inventory": True,
			"original_artifact_path": str(self.artifact),
			"disposable_working_copy_path": str(self.root / "work"),
			"operator": "Acme archival operator",
			"execution_environment": "production node host",
			"database_files": [self.file_entry],
			"directory_fingerprint_sha256": validation.directory_fingerprint([self.file_entry]),
			"provenance": {
				"source": "operator-provided source identity and capture statement",
				"acquisition_date": "2026-09-26T12:15:00Z",
				"snapshot_date": "2026-09-26T12:00:00Z",
				"snapshot_height": 123,
				"observed_chain_height": 123,
				"snapshot_block_hash": _hash("tip"),
				"observed_tip_block_hash": _hash("tip"),
				"expected_genesis_block_hash": _hash("genesis"),
				"observed_genesis_block_hash": _hash("genesis"),
				"expected_genesis_evidence": "separately trusted genesis reference",
				"observed_genesis_evidence": "read-only DB fingerprint",
				"quiesced_evidence": "signed shutdown/H2 close records",
				"quiesced": True,
				"observed_network": "mainnet",
				"observed_network_version": "0x68",
				"legacy_nis_version": "0.6.100",
				"legacy_java_version": "11.0.20",
				"legacy_h2_version": "1.4.200",
				"legacy_flyway_version": "3.2.1",
			},
			"source": {
				"organization": "Acme archival operator", "operator_id": "acme-db-ops",
				"system_id": "mainnet-node-17", "node_id": "nis-mainnet-17",
				"network": "mainnet", "database_path": "/srv/nis/data/nis.mv.db",
				"nis_version": "0.6.100", "h2_version": "1.4.200",
			},
			"acquisition": {
				"method": "copy-after-normal-shutdown",
				"acquisition_timestamp": "2026-09-26T12:15:00Z",
				"snapshot_timestamp": "2026-09-26T12:00:00Z",
				"observed_chain_height": 123, "observed_tip_block_hash": _hash("tip"),
				"db_state_at_acquisition": {"nis_process": "stopped", "h2_writer": "closed"},
			},
			"quiescence": {
				"method": "normal-nis-shutdown-h2-close",
				"nis_shutdown_completed_at": "2026-09-26T11:50:00Z",
				"h2_close_completed_at": "2026-09-26T11:55:00Z",
			},
			"independent_checkpoint": {
				"source_id": "archive-checkpoint-operator-9",
				"source_operator_id": "archive-checkpoint-ops",
				"source_url": "https://archive.example.invalid/nem/checkpoint/123",
				"network": "mainnet", "height": 123, "block_hash": _hash("tip"),
				"genesis_block_hash": _hash("genesis"),
				"retrieved_at": "2026-09-27T00:00:00Z", "evidence_id": "checkpoint_record",
				"signature_path": "checkpoint.json.sig", "signer_id": "archive-checkpoint-ops",
				"public_key_sha256": validation._public_key_fingerprint(self.checkpoint_public),
			},
			"authentication": {
				"scheme": "ed25519-detached-manifest-v1", "signer_id": "acme-db-ops",
				"public_key_sha256": validation._public_key_fingerprint(self.operator_public),
				"signature_path": "manifest.json.sig",
			},
		}
		self.evidence = []
		self._add_json_evidence("source_identity", "source_identity_record", {
			"organization": "Acme archival operator", "operator_id": "acme-db-ops",
			"system_id": "mainnet-node-17", "node_id": "nis-mainnet-17", "network": "mainnet",
			"nis_version": "0.6.100", "h2_version": "1.4.200"})
		self._add_json_evidence("acquisition_record", "acquisition_record", {
			"network": "mainnet", "source_system_id": "mainnet-node-17",
			"source_database_path": "/srv/nis/data/nis.mv.db",
			"acquired_at": "2026-09-26T12:15:00Z", "source_files": [dict(self.file_entry)],
			"received_files": [dict(self.file_entry)], "observed_chain_height": 123,
			"observed_tip_block_hash": _hash("tip")})
		self._add_json_evidence("database_fingerprint", "database_fingerprint", {
			"format": "nem-nis-chain-state-v1", "network": "mainnet", "network_version": "0x68",
			"genesis_block_hash": _hash("genesis"), "height": 123, "tip_block_hash": _hash("tip")})
		self._add_json_evidence("nis_shutdown", "nis_shutdown_record", {
			"event": "nis_shutdown_completed", "source_system_id": "mainnet-node-17",
			"network": "mainnet", "completed_at": "2026-09-26T11:50:00Z", "exit_code": 0})
		self._add_json_evidence("h2_close", "h2_close_record", {
			"event": "h2_close_completed", "source_system_id": "mainnet-node-17",
			"database_path": "/srv/nis/data/nis.mv.db", "completed_at": "2026-09-26T11:55:00Z",
			"database_closed": True})
		self._add_json_evidence("checkpoint_record", "signed_checkpoint_record", {
			"source_id": "archive-checkpoint-operator-9", "source_operator_id": "archive-checkpoint-ops",
			"source_url": "https://archive.example.invalid/nem/checkpoint/123", "network": "mainnet",
			"height": 123, "block_hash": _hash("tip"), "genesis_block_hash": _hash("genesis"),
			"retrieved_at": "2026-09-27T00:00:00Z"})
		self._write_checkpoint_signature()
		self._seal_manifest()

	def tearDown(self):
		self.temp.cleanup()

	def _make_key(self, name):
		private_key = self.root / f"{name}.private.pem"
		public_key = self.root / f"{name}.public.pem"
		subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(private_key)],
				check=True, capture_output=True)
		subprocess.run(["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
				check=True, capture_output=True)
		return private_key, public_key

	def _add_json_evidence(self, evidence_id, kind, value):
		path = self.bundle / f"{evidence_id}.json"
		path.write_text(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
		entry = {"id": evidence_id, "kind": kind, "path": path.name, "size_bytes": path.stat().st_size,
				"sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
		self.evidence = [item for item in self.evidence if item["id"] != evidence_id]
		self.evidence.append(entry)
		self.manifest["evidence_files"] = self.evidence
		return path

	def _write_checkpoint_signature(self):
		path = self.bundle / "checkpoint_record.json"
		# Keep the signed bytes identical to the evidence-file bytes.
		checkpoint = {key: self.manifest["independent_checkpoint"][key]
				for key in ("source_id", "source_operator_id", "source_url", "network", "height", "block_hash",
						"genesis_block_hash", "retrieved_at")}
		path.write_text(json.dumps(checkpoint, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
		entry = next(item for item in self.evidence if item["id"] == "checkpoint_record")
		entry["size_bytes"] = path.stat().st_size
		entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
		subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(self.checkpoint_private),
				"-in", str(path), "-out", str(self.bundle / "checkpoint.json.sig")], check=True, capture_output=True)

	def _seal_manifest(self):
		manifest_path = self.bundle / "manifest.json"
		manifest_path.write_text(json.dumps(self.manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
		subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(self.operator_private),
				"-in", str(manifest_path), "-out", str(self.bundle / "manifest.json.sig")], check=True, capture_output=True)

	def _rewrite_checkpoint_and_seal(self):
		self._write_checkpoint_signature()
		self._seal_manifest()

	def _evaluate(self):
		return validation.validate_evidence_manifest(self.bundle / "manifest.json", self.artifact,
				self.operator_public, "acme-db-ops", self.checkpoint_public, "archive-checkpoint-ops")

	def test_dual_authenticated_hash_bound_evidence_passes(self):
		result = self._evaluate()
		self.assertEqual("EVIDENCE_CONTRACT_PASS_REQUIRES_PHASE2F_PAIR_GATE", result["decision"])
		self.assertEqual("pass", result["provenance"])
		self.assertEqual("pass", result["quiescence"])
		self.assertEqual("pass", result["independent_checkpoint"])

	def test_pair_evaluator_does_not_accept_two_mainnet_bundles(self):
		args = (self.bundle / "manifest.json", self.artifact, self.operator_public, "acme-db-ops",
				self.checkpoint_public, "archive-checkpoint-ops")
		with self.assertRaisesRegex(validation.ValidationError, "Testnet evidence input"):
			validation.validate_evidence_pair(args, args)

	def test_pair_evaluator_requires_both_authenticated_networks(self):
		other = EvidenceContractTest()
		other.setUp()
		try:
			other.payload = b"different test-only fixture bytes"
			(other.artifact / "nis.mv.db").write_bytes(other.payload)
			other.file_entry = {"path": "nis.mv.db", "size_bytes": len(other.payload),
					"sha256": hashlib.sha256(other.payload).hexdigest()}
			other.manifest["network"] = "testnet"
			other.manifest["database_files"] = [other.file_entry]
			other.manifest["directory_fingerprint_sha256"] = validation.directory_fingerprint([other.file_entry])
			other.manifest["provenance"].update({"observed_network": "testnet", "observed_network_version": "0x98"})
			other.manifest["source"]["network"] = "testnet"
			other.manifest["independent_checkpoint"]["network"] = "testnet"
			other.manifest["acquisition"]["observed_chain_height"] = 123
			for evidence_id, updates in {
				"source_identity": {"network": "testnet"},
				"acquisition_record": {"network": "testnet", "source_files": [other.file_entry],
						"received_files": [other.file_entry]},
				"database_fingerprint": {"network": "testnet", "network_version": "0x98"},
				"nis_shutdown": {"network": "testnet"},
				"checkpoint_record": {"network": "testnet"},
			}.items():
				entry = next(item for item in other.evidence if item["id"] == evidence_id)
				path = other.bundle / entry["path"]
				content = json.loads(path.read_text(encoding="utf-8"))
				content.update(updates)
				path.write_text(json.dumps(content, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
				entry["size_bytes"] = path.stat().st_size
				entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
			other._rewrite_checkpoint_and_seal()
			main_args = (self.bundle / "manifest.json", self.artifact, self.operator_public, "acme-db-ops",
					self.checkpoint_public, "archive-checkpoint-ops")
			test_args = (other.bundle / "manifest.json", other.artifact, other.operator_public, "acme-db-ops",
					other.checkpoint_public, "archive-checkpoint-ops")
			result = validation.validate_evidence_pair(main_args, test_args)
			self.assertEqual("PROVENANCE_QUIESCENCE_EVIDENCE_PASS_BOTH_NETWORKS", result["decision"])
		finally:
			other.tearDown()

	def test_missing_provenance_field_is_rejected(self):
		del self.manifest["source"]["node_id"]
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "source.node_id"):
			self._evaluate()

	def test_missing_quiescence_record_is_rejected(self):
		self.manifest["evidence_files"] = [entry for entry in self.evidence if entry["id"] != "h2_close"]
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "H2 close evidence"):
			self._evaluate()

	def test_wrong_artifact_hash_is_rejected(self):
		self.manifest["database_files"][0]["sha256"] = _hash("other artifact")
		self.manifest["directory_fingerprint_sha256"] = validation.directory_fingerprint(self.manifest["database_files"])
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "SHA-256 mismatch"):
			self._evaluate()

	def test_provenance_for_another_artifact_is_rejected(self):
		self.manifest["source"]["database_path"] = "/srv/nis/other.mv.db"
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "acquisition record does not match"):
			self._evaluate()

	def test_wrong_network_is_rejected(self):
		self.manifest["network"] = "testnet"
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "observed network identity"):
			self._evaluate()

	def test_checkpoint_mismatch_is_rejected(self):
		self.manifest["independent_checkpoint"]["block_hash"] = _hash("unrelated checkpoint")
		self._rewrite_checkpoint_and_seal()
		with self.assertRaisesRegex(validation.ValidationError, "independent checkpoint does not match"):
			self._evaluate()

	def test_unpinned_operator_identity_is_rejected(self):
		self.manifest["authentication"]["signer_id"] = "untrusted-operator"
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "externally pinned identity"):
			self._evaluate()

	def test_unauthenticated_evidence_is_rejected(self):
		self.manifest["authentication"]["scheme"] = "none"
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "unsupported or unauthenticated"):
			self._evaluate()

	def test_checkpoint_signer_cannot_be_the_artifact_operator(self):
		self.manifest["independent_checkpoint"]["signer_id"] = "acme-db-ops"
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "operator and checkpoint signer must be distinct"):
			validation.validate_evidence_manifest(self.bundle / "manifest.json", self.artifact,
					self.operator_public, "acme-db-ops", self.checkpoint_public, "acme-db-ops")

	def test_wrong_artifact_size_is_rejected(self):
		self.manifest["database_files"][0]["size_bytes"] += 1
		self.manifest["directory_fingerprint_sha256"] = validation.directory_fingerprint(self.manifest["database_files"])
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "size mismatch"):
			self._evaluate()

	def test_modified_quiescence_evidence_is_rejected(self):
		(self.bundle / "h2_close.json").write_text("{}", encoding="utf-8")
		with self.assertRaisesRegex(validation.ValidationError, "evidence size mismatch|evidence SHA-256 mismatch"):
			self._evaluate()

	def test_unquiesced_claim_is_rejected(self):
		self.manifest["acquisition"]["db_state_at_acquisition"]["h2_writer"] = "running"
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "closed H2 writer"):
			self._evaluate()

	def test_snapshot_cannot_precede_h2_close(self):
		self.manifest["quiescence"]["h2_close_completed_at"] = "2026-09-26T12:05:00Z"
		self._add_json_evidence("h2_close", "h2_close_record", {
			"event": "h2_close_completed", "source_system_id": "mainnet-node-17",
			"database_path": "/srv/nis/data/nis.mv.db", "completed_at": "2026-09-26T12:05:00Z",
			"database_closed": True})
		self._seal_manifest()
		with self.assertRaisesRegex(validation.ValidationError, "no later than snapshot time"):
			self._evaluate()

	def test_malformed_manifest_is_rejected(self):
		(self.bundle / "manifest.json").write_text("{", encoding="utf-8")
		with self.assertRaisesRegex(validation.ValidationError, "cannot read manifest JSON"):
			self._evaluate()

	def test_manifest_change_after_signature_is_rejected(self):
		self.manifest["operator"] = "changed after signature"
		(self.bundle / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
		with self.assertRaisesRegex(validation.ValidationError, "manifest signature verification failed"):
			self._evaluate()

	def test_checkpoint_timestamp_after_snapshot_is_reproducible_and_signed(self):
		self.manifest["independent_checkpoint"]["retrieved_at"] = "2026-09-27T00:00:00Z"
		self._rewrite_checkpoint_and_seal()
		self.assertEqual("pass", self._evaluate()["independent_checkpoint"])


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
