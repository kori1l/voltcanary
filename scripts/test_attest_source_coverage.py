import hashlib
import importlib.util
import json
import sqlite3
import tempfile
import threading
import unittest
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, HTTPServer


MODULE_PATH = Path(__file__).with_name("attest-source-coverage.py")
SPEC = importlib.util.spec_from_file_location("coverage_attestor", MODULE_PATH)
attestor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(attestor)


def utc(value):
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")


class CoverageAttestorTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        root = Path(self.folder.name)
        self.state = root / "proof.sqlite"
        self.evidence = root / "closed.ndjson"
        now = datetime.now(timezone.utc).replace(microsecond=0)
        self.start = utc(now - timedelta(minutes=2))
        self.end = utc(now - timedelta(minutes=1))
        self.evidence.write_text("\n".join(json.dumps({"sequence": str(i), "eventTime": self.start})
                                           for i in (1, 2)) + "\n", encoding="utf-8")
        self.manifest = root / "statement.json"
        self.value = {"profile": "source-coverage-statement-1", "proofId": str(uuid.uuid4()),
                      "bindingId": str(uuid.uuid4()), "accountId": str(uuid.uuid4()),
                      "deviceId": str(uuid.uuid4()), "schemaRevision": 1,
                      "snapshotId": str(uuid.uuid4()), "producerEpoch": str(uuid.uuid4()),
                      "throughSequence": "2", "from": self.start, "until": self.end,
                      "evidenceFile": self.evidence.name,
                      "evidenceSha256": hashlib.sha256(self.evidence.read_bytes()).hexdigest()}
        self.manifest.write_text(json.dumps(self.value), encoding="utf-8")
        self.statement, self.digest = attestor.read_manifest(self.manifest)
        self.generation = str(uuid.uuid4())
        self.pin = {"bindingId": self.value["bindingId"], "accountId": self.value["accountId"],
                    "deviceId": self.value["deviceId"], "snapshotId": self.value["snapshotId"],
                    "schemaRevision": 1}

    def capture(self):
        return {"source": {"id": self.value["bindingId"], "accountId": self.value["accountId"],
                           "deviceId": self.value["deviceId"], "sourceActive": True},
                "capture": {"pin": self.pin, "journalReady": True, "inputGeneration": self.generation}}

    def covered(self):
        return {"coverage": "COVERED", "proofId": self.value["proofId"],
                "inputGeneration": self.generation, "snapshotId": self.value["snapshotId"],
                "evidence": {"position": {"epoch": self.value["producerEpoch"], "sequence": "2"},
                             "from": self.start, "until": self.end}}

    def advance(self):
        return attestor.advance(self.statement, self.digest, self.state, "http://device", "http://telemetry",
                                "owner-secret", "attestor-secret")

    def test_gap_recovery_restart_and_late_contradiction(self):
        calls = []
        reads = 0

        def first(base, path, header, token, body=None):
            nonlocal reads
            calls.append((base, path, header))
            if "/capture?" in path:
                return self.capture()
            if base == "http://device":
                reads += 1
                return {"coverage": "UNKNOWN", "reason": "PROOF_UNAVAILABLE" if reads == 1 else "SOURCE_GAP"}
            self.assertEqual("X-Coverage-Attestor-Token", header)
            self.assertEqual(self.value["proofId"], body["proofId"])
            return {"coverage": "UNKNOWN", "reason": "SOURCE_GAP"}

        with patch.object(attestor, "request", side_effect=first):
            self.assertEqual("UNKNOWN", self.advance())
        self.assertEqual(4, len(calls))
        self.assertNotIn(b"owner-secret", self.state.read_bytes())
        self.assertNotIn(b"attestor-secret", self.state.read_bytes())

        def recovered(base, path, header, token, body=None):
            return self.capture() if "/capture?" in path else self.covered()

        with patch.object(attestor, "request", side_effect=recovered):
            self.assertEqual("COVERED", self.advance())
        with sqlite3.connect(self.state) as db:
            self.assertEqual("COVERED", db.execute("SELECT status FROM proof").fetchone()[0])

        def contradicted(base, path, header, token, body=None):
            return self.capture() if "/capture?" in path else {"coverage": "UNKNOWN", "reason": "CONTRADICTORY_OBSERVATION"}

        with patch.object(attestor, "request", side_effect=contradicted):
            self.assertEqual("UNKNOWN", self.advance())
        with sqlite3.connect(self.state) as db:
            self.assertEqual("PENDING", db.execute("SELECT status FROM proof").fetchone()[0])

    def test_changed_statement_and_generation_fail_closed(self):
        with patch.object(attestor, "request", side_effect=lambda base, path, header, token, body=None:
                          self.capture() if "/capture?" in path else {"coverage": "UNKNOWN", "reason": "SOURCE_GAP"}):
            self.assertEqual("UNKNOWN", self.advance())
        with self.assertRaisesRegex(ValueError, "different statement"):
            attestor.advance(self.statement, "0" * 64, self.state, "http://device", "http://telemetry", "a", "b")
        self.generation = str(uuid.uuid4())
        with patch.object(attestor, "request", return_value=self.capture()):
            with self.assertRaisesRegex(ValueError, "generation changed"):
                self.advance()

    def test_revocation_after_attestation_cannot_mark_covered(self):
        reads = 0

        def revoked(base, path, header, token, body=None):
            nonlocal reads
            if "/capture?" in path:
                return self.capture()
            if base == "http://telemetry":
                return self.covered()
            reads += 1
            if reads == 1:
                return {"coverage": "UNKNOWN", "reason": "PROOF_UNAVAILABLE"}
            raise ValueError("Owner authorization or statement conflict")

        with patch.object(attestor, "request", side_effect=revoked):
            with self.assertRaisesRegex(ValueError, "Owner authorization"):
                self.advance()
        with sqlite3.connect(self.state) as db:
            self.assertEqual("PENDING", db.execute("SELECT status FROM proof").fetchone()[0])

    def test_source_evidence_digest_and_prefix_are_required(self):
        self.evidence.write_text(json.dumps({"sequence": "2", "eventTime": self.start}) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Source evidence changed"):
            attestor.read_manifest(self.manifest)
        self.value["evidenceSha256"] = hashlib.sha256(self.evidence.read_bytes()).hexdigest()
        self.manifest.write_text(json.dumps(self.value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Complete producer prefix required"):
            attestor.read_manifest(self.manifest)

    def test_authorization_is_not_forwarded_across_redirect(self):
        seen = []

        class Target(BaseHTTPRequestHandler):
            def do_GET(self):
                seen.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()

            def log_message(self, *args):
                pass

        target = HTTPServer(("127.0.0.1", 0), Target)
        self.addCleanup(target.server_close)
        target_thread = threading.Thread(target=target.serve_forever, daemon=True)
        target_thread.start()
        self.addCleanup(target.shutdown)

        class Redirect(Target):
            def do_GET(self):
                self.send_response(302)
                self.send_header("Location", f"http://127.0.0.1:{target.server_port}/stolen")
                self.end_headers()

        origin = HTTPServer(("127.0.0.1", 0), Redirect)
        self.addCleanup(origin.server_close)
        origin_thread = threading.Thread(target=origin.serve_forever, daemon=True)
        origin_thread.start()
        self.addCleanup(origin.shutdown)
        with self.assertRaisesRegex(ValueError, "redirect refused"):
            attestor.request(f"http://127.0.0.1:{origin.server_port}", "/capture", "Authorization", "Bearer secret")
        self.assertEqual([], seen)

    def test_real_http_submission_then_signed_owner_read(self):
        events = []
        fixture = self

        class Owner(BaseHTTPRequestHandler):
            def do_GET(self):
                fixture.assertEqual("Bearer owner-secret", self.headers.get("Authorization"))
                events.append("capture")
                self.send_json(fixture.capture())

            def do_POST(self):
                fixture.assertEqual("Bearer owner-secret", self.headers.get("Authorization"))
                fixture.assertEqual(fixture.pin, json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                events.append("read")
                self.send_json({"coverage": "UNKNOWN", "reason": "PROOF_UNAVAILABLE"} if events.count("read") == 1
                               else fixture.covered())

            def send_json(self, value):
                body = json.dumps(value).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        class Telemetry(Owner):
            def do_POST(self):
                fixture.assertEqual("attestor-secret", self.headers.get("X-Coverage-Attestor-Token"))
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fixture.assertEqual(fixture.value["proofId"], payload["proofId"])
                events.append("attest")
                self.send_json({"coverage": "UNKNOWN", "reason": "SOURCE_GAP"})

        owner = HTTPServer(("127.0.0.1", 0), Owner)
        telemetry = HTTPServer(("127.0.0.1", 0), Telemetry)
        for server in (owner, telemetry):
            self.addCleanup(server.server_close)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            self.addCleanup(server.shutdown)
        outcome = attestor.advance(self.statement, self.digest, self.state,
                                   f"http://127.0.0.1:{owner.server_port}",
                                   f"http://127.0.0.1:{telemetry.server_port}",
                                   "owner-secret", "attestor-secret")
        self.assertEqual("COVERED", outcome)
        self.assertEqual(["capture", "read", "attest", "read"], events)


if __name__ == "__main__":
    unittest.main()
