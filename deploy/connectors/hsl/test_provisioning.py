import io
from unittest.mock import patch
import json
import tempfile
import unittest
import http.client
from pathlib import Path
from uuid import uuid4
from provisioning import Provisioning, SetupError, start_control


class ProvisioningTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "config.json"
        self.path.write_text(json.dumps({"vehicles": []}))
        self.device = str(uuid4())
        self.source = None
        self.sources_created = self.tokens = 0
        self.fresh = False
        self.denied = False
        self.fail_token = False
        self.body = {"mode": "bus", "vehicleKey": "0012-02512", "creationKey": "reviewed-key"}
        self.service = Provisioning(self.path, "http://devices", "http://telemetry", self.api)

    def test_deletion_requires_internal_authorization_and_removes_only_target_binding(self):
        other = str(uuid4())
        self.path.write_text(json.dumps({"vehicles": [
            {"deviceId": self.device, "key": "0012-02512", "mode": "bus", "sourceToken": "target-secret"},
            {"deviceId": other, "key": "0012-02618", "mode": "bus", "sourceToken": "preserved-secret"}]}))
        before = self.path.read_text()
        with self.assertRaises(SetupError):
            self.service.delete_binding(self.device, "wrong-token")
        self.assertEqual(before, self.path.read_text())
        for attempt in range(2):
            response = io.BytesIO(json.dumps({"deviceId": self.device, "deleteHistory": True}).encode())
            with patch("provisioning.urlopen", return_value=response):
                self.assertEqual({"status": "COMPLETED"}, self.service.delete_binding(self.device, "dev-internal-token"))
        remaining = json.loads(self.path.read_text())["vehicles"]
        self.assertEqual(1, len(remaining))
        self.assertEqual(other, remaining[0]["deviceId"])
        self.assertEqual("preserved-secret", remaining[0]["sourceToken"])

    def api(self, base, method, path, auth, body=None):
        if auth != "Bearer owner" or self.denied:
            raise SetupError(404)
        if path == "/accounts/current/scope":
            return {"id": "account"}
        if path == "/devices/telemetry-templates":
            return [{"id": "template", "name": "HSL transport", "deviceType": "VEHICLE", "policy": Provisioning.default_policy()}]
        if path == "/devices/telemetry-templates/assign":
            self.assertEqual(self.source["id"], body["targets"][0]["sourceId"])
            return None
        if path.endswith("/token"):
            if self.fail_token:
                raise SetupError(503)
            self.tokens += 1
            return {"token": "private-ingestion-value"}
        if path.endswith("/positions/latest?sourceId=hsl-bus-0012-02512"):
            return {"freshness": "FRESH" if self.fresh else "NO_DATA", "position": {"latitude": 60} if self.fresh else None}
        if path.endswith("/data-sources"):
            if method == "POST":
                self.sources_created += 1
                self.source = {"id": str(uuid4()), **body}
                return self.source
            return [self.source] if self.source else []
        if "/data-sources/" in path:
            return self.source
        if path == "/devices/" + self.device:
            return {"id": self.device, "type": "VEHICLE", "status": "ACTIVE"}
        self.fail(path)

    def bind(self):
        return self.service.bind({**self.body, "deviceId": self.device}, "Bearer owner")

    def test_waiting_and_live_status_use_actual_evidence_and_replay_has_no_side_effects(self):
        self.service.reserve(self.body, "Bearer owner")
        self.assertEqual("WAITING_FOR_DATA", self.bind()["setupStatus"])
        self.service.reserve(self.body, "Bearer owner")
        self.fresh = True
        result = self.bind()
        self.assertEqual("TELEMETRY_READY", result["setupStatus"])
        self.assertNotIn("private-ingestion-value", json.dumps(result))
        self.assertEqual((1, 1), (self.sources_created, self.tokens))

    def test_conflicting_task_is_rejected_before_creating_another_device(self):
        self.service.reserve(self.body, "Bearer owner")
        with self.assertRaises(SetupError) as error:
            self.service.reserve({**self.body, "creationKey": "another-task"}, "Bearer owner")
        self.assertEqual(409, error.exception.status)

    def test_foreign_authorization_cannot_read_or_mutate_binding(self):
        self.service.reserve(self.body, "Bearer owner")
        self.bind()
        before = self.path.read_bytes()
        with self.assertRaises(SetupError):
            self.service.status(self.device, "Bearer foreign")
        self.denied = True
        with self.assertRaises(SetupError):
            self.bind()
        self.assertEqual(before, self.path.read_bytes())

    def test_retry_after_partial_source_creation_reuses_source(self):
        self.service.reserve(self.body, "Bearer owner")
        self.fail_token = True
        with self.assertRaises(SetupError):
            self.bind()
        self.fail_token = False
        self.service.reserve(self.body, "Bearer owner")
        self.bind()
        self.assertEqual((1, 1), (self.sources_created, self.tokens))

    def test_existing_vehicles_and_tokens_are_preserved(self):
        previous = {"key": "0012-02417", "deviceId": str(uuid4()), "sourceToken": "keep", "mode": "bus"}
        self.path.write_text(json.dumps({"vehicles": [previous]}))
        self.service.reserve(self.body, "Bearer owner")
        self.bind()
        self.assertEqual(previous, json.loads(self.path.read_text())["vehicles"][0])

    def test_bounds_and_unknown_fields_are_rejected(self):
        for body in ({**self.body, "url": "http://other"}, {**self.body, "mode": "train"}, {**self.body, "vehicleKey": "bad"}):
            with self.assertRaises(SetupError):
                self.service.reserve(body, "Bearer owner")
        self.path.write_text(json.dumps({"vehicles": [{"key": f"0001-{i:05}", "mode": "bus"} for i in range(20)]}))
        with self.assertRaises(SetupError) as error:
            self.service.reserve(self.body, "Bearer owner")
        self.assertEqual(409, error.exception.status)

    def test_private_http_accepts_java_chunked_json_and_rejects_oversized_body(self):
        server = start_control(self.service, port=0)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        connection = http.client.HTTPConnection("127.0.0.1", server.server_address[1], timeout=3)
        self.addCleanup(connection.close)
        body = json.dumps(self.body).encode()
        connection.request("POST", "/availability", body=iter([body[:20], body[20:]]),
                           headers={"Authorization": "Bearer owner", "Content-Type": "application/json"}, encode_chunked=True)
        response = connection.getresponse()
        self.assertEqual(200, response.status)
        response.read()
        connection.request("POST", "/availability", body=iter([b"x" * 5000]),
                           headers={"Authorization": "Bearer owner"}, encode_chunked=True)
        self.assertEqual(400, connection.getresponse().status)


if __name__ == "__main__":
    unittest.main()
