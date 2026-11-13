"""Exercise a qualified closed source prefix through real ingestion and owner APIs."""

import argparse
import hashlib
import importlib.util
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from ai_dashboard_eval.api import Client, ROOT


SPEC = importlib.util.spec_from_file_location("coverage_attestor", Path(__file__).with_name("attest-source-coverage.py"))
attestor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(attestor)
CAPTURE_RECONFIGURED = False


def require(condition, code):
    if not condition:
        raise RuntimeError(code)


def until_ready(url, timeout=900):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url + "/actuator/health", timeout=3) as response:
                if response.status == 200:
                    return
        except (OSError, ValueError):
            pass
        time.sleep(2)
    raise RuntimeError("SERVICE_NOT_READY")


def restart_capture(binding, token):
    global CAPTURE_RECONFIGURED
    env = os.environ.copy()
    env.update({"OBSERVATION_CAPTURE_ENABLED": "true", "OBSERVATION_CAPTURE_BINDING_IDS": binding,
                "APP_INTERNAL_COVERAGE_ATTESTOR_TOKEN": token})
    result = subprocess.run(["docker", "compose", "--profile", "app", "up", "-d", "--no-deps", "--force-recreate",
                             "telemetry-service"], cwd=ROOT, env=env, capture_output=True, timeout=180)
    require(result.returncode == 0, "TELEMETRY_RESTART_FAILED")
    CAPTURE_RECONFIGURED = True
    until_ready("http://127.0.0.1:8084")


def restore_capture():
    result = subprocess.run(["docker", "compose", "--profile", "app", "up", "-d", "--no-deps", "--force-recreate",
                             "telemetry-service"], cwd=ROOT, capture_output=True, timeout=180)
    require(result.returncode == 0, "TELEMETRY_RESTORE_FAILED")
    until_ready("http://127.0.0.1:8084")


def ingest(source_token, device, source, epoch, sequence, event_time, metrics):
    body = {"deviceId": device, "sourceId": source, "timestamp": event_time,
            "sourcePosition": {"epoch": epoch, "sequence": str(sequence)}, "metrics": metrics}
    call = urllib.request.Request("http://127.0.0.1:8083/ingestion/readings",
                                  json.dumps(body).encode(), {"Authorization": "Bearer " + source_token,
                                                             "Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(call, timeout=15) as response:
            value = json.loads(response.read(8192))
            require(response.status == 202 and value["acceptedCount"] == 1, "INGESTION_NOT_ACCEPTED")
    except urllib.error.HTTPError as failure:
        raise RuntimeError("INGESTION_HTTP_" + str(failure.code)) from None


def wait_capture(owner_token, binding):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            value = attestor.request("http://127.0.0.1:8082", f"/data/source-bindings/{binding}/capture?schemaRevision=1",
                                     "Authorization", "Bearer " + owner_token)
            if value["capture"]["journalReady"]:
                return value
        except RuntimeError:
            pass
        time.sleep(2)
    raise RuntimeError("SOURCE_CAPTURE_NOT_READY")


def wait_status(statement, digest, state, owner_token, attestor_token, desired):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        result = attestor.advance(statement, digest, state, "http://127.0.0.1:8082", "http://127.0.0.1:8084",
                                  owner_token, attestor_token)
        if result == desired:
            return
        time.sleep(2)
    raise RuntimeError("COVERAGE_STATUS_TIMEOUT")


def run():
    for port in (8080, 8081, 8082, 8083, 8084):
        until_ready(f"http://127.0.0.1:{port}")
    anonymous = Client("http://127.0.0.1:8080", "", timeout=30)
    suffix = uuid.uuid4().hex
    email = "coverage-smoke-" + suffix + "@example.test"
    password = "Coverage!" + secrets.token_hex(18)
    anonymous.call("POST", "/auth/register", {"email": email, "password": password})
    login = anonymous.call("POST", "/auth/login", {"email": email, "password": password})
    client = Client("http://127.0.0.1:8080", login["accessToken"], timeout=30,
                    refresh_token=login.get("refreshToken"))
    account = client.call("GET", "/accounts/current")["id"]
    device_type = client.call("GET", "/device-types")[0]["code"]
    device = client.call("POST", "/devices", {"name": "Coverage smoke source", "type": device_type, "metadata": {}},
                         headers={"Idempotency-Key": suffix + "-device"})
    fields = [{"path": "subject", "key": "subject", "label": "Subject", "type": "string", "unit": "1", "store": True, "display": True},
              {"path": "occurrence", "key": "occurrence", "label": "Occurrence", "type": "string", "unit": "1", "store": True, "display": True},
              {"path": "doorOpen", "key": "doorOpen", "label": "Door open", "type": "boolean", "unit": "1", "store": True, "display": True}]
    routing = {"version": "direct-occurrence-1", "subjectKey": "subject", "occurrenceKey": "occurrence"}
    source = client.call("POST", f"/devices/{device['id']}/data-sources", {
        "sourceType": "CUSTOM", "protocol": "REST", "externalId": "coverage-smoke-" + suffix,
        "mapping": {"telemetryPolicy": {"fields": fields}, "occurrenceRouting": routing}, "adapterConfig": {}, "credentialReferences": {}})
    definition = client.call("GET", f"/data/source-bindings/{source['id']}?schemaRevision=1")["definition"]
    require(definition["occurrenceRouting"] == routing, "OCCURRENCE_ROUTING_NOT_PUBLISHED")
    occurrence_field = next(field["fieldId"] for field in definition["fieldMappings"] if field["sourceKey"] == "occurrence")
    source_token = client.call("POST", f"/devices/{device['id']}/data-sources/{source['id']}/token")["token"]
    attestor_token = "coverage-" + secrets.token_hex(32)
    restart_capture(source["id"], attestor_token)
    client.prepare_planning_session()
    binding = wait_capture_schema(client.token, source["id"])
    require(binding["source"]["accountId"] == account, "SOURCE_ACCOUNT_CHANGED")
    snapshot = binding["source"]["snapshotId"]
    epoch = str(uuid.uuid4())
    now = datetime.now(timezone.utc).replace(microsecond=0)
    start = now - timedelta(seconds=45)
    end = now - timedelta(seconds=35)
    event_times = [start + timedelta(seconds=i) for i in (1, 2, 3)]
    metrics = {"subject": "cabinet", "occurrence": "trip-1", "doorOpen": True}
    folder = ROOT / "artifacts" / ("coverage-smoke-" + suffix)
    folder.mkdir(parents=True, exist_ok=False)
    evidence = folder / "closed.ndjson"
    producer_log = [{"sequence": str(index), "eventTime": event_times[index - 1].isoformat().replace("+00:00", "Z")}
                    for index in (1, 2, 3)]
    evidence.write_text("".join(json.dumps(row) + "\n" for row in producer_log), encoding="utf-8")
    manifest = folder / "statement.json"
    manifest.write_text(json.dumps({"profile": "source-coverage-statement-1", "proofId": str(uuid.uuid4()),
                                    "bindingId": source["id"], "accountId": account, "deviceId": device["id"],
                                    "schemaRevision": 1, "snapshotId": snapshot, "producerEpoch": epoch,
                                    "throughSequence": "3", "from": start.isoformat().replace("+00:00", "Z"),
                                    "until": end.isoformat().replace("+00:00", "Z"),
                                    "evidenceFile": evidence.name, "evidenceSha256": hashlib.sha256(evidence.read_bytes()).hexdigest()}),
                        encoding="utf-8")
    statement, digest = attestor.read_manifest(manifest)
    state = folder / "proof.sqlite"
    for sequence in (1, 3):
        ingest(source_token, device["id"], source["externalId"], epoch, sequence,
               producer_log[sequence - 1]["eventTime"], metrics)
    client.prepare_planning_session()
    wait_capture(client.token, source["id"])
    require(attestor.advance(statement, digest, state, "http://127.0.0.1:8082", "http://127.0.0.1:8084",
                             client.token, attestor_token) == "UNKNOWN", "GAP_NOT_UNKNOWN")
    restart_capture(source["id"], attestor_token)
    client.prepare_planning_session()
    require(attestor.advance(statement, digest, state, "http://127.0.0.1:8082", "http://127.0.0.1:8084",
                             client.token, attestor_token) == "UNKNOWN", "RESTART_LOST_GAP")
    ingest(source_token, device["id"], source["externalId"], epoch, 2, producer_log[1]["eventTime"], metrics)
    wait_status(statement, digest, state, client.token, attestor_token, "COVERED")
    ingest(source_token, device["id"], source["externalId"], epoch, 4, producer_log[0]["eventTime"],
           {**metrics, "occurrence": ""})
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        proof = attestor.request("http://127.0.0.1:8082", f"/data/source-bindings/{source['id']}/coverage/{statement['proofId']}",
                                 "Authorization", "Bearer " + client.token, binding["capture"]["pin"])
        if proof["coverage"] == "UNKNOWN" and proof["reason"] == "CONTRADICTORY_OBSERVATION":
            capture = wait_capture(client.token, source["id"])["capture"]
            page = client.call("POST", f"/data/source-bindings/{source['id']}/observations", {
                "pin": capture["pin"], "inputGeneration": capture["inputGeneration"],
                "afterCheckpoint": capture["generationStartCheckpoint"], "throughCheckpoint": capture["headCheckpoint"], "limit": 128})
            identities = [next(field for field in event["fields"] if field["fieldId"] == occurrence_field)
                          for event in page["observations"]]
            require(len(identities) == 4 and all(field["knowledge"] == "KNOWN" and field["value"] == "trip-1"
                    for field in identities[:3]), "OCCURRENCE_IDENTITY_NOT_CAPTURED")
            require(identities[-1]["knowledge"] == "UNKNOWN" and identities[-1]["value"] is None
                    and identities[-1]["reason"] == "MISSING_OR_NULL_INPUT", "BLANK_OCCURRENCE_NOT_UNKNOWN")
            print(json.dumps({"status": "passed", "accountId": account, "deviceId": device["id"],
                              "bindingId": source["id"], "proofId": statement["proofId"],
                              "occurrenceRoutingVerified": True, "blankOccurrenceUnknown": True}))
            return
        time.sleep(2)
    raise RuntimeError("LATE_CONTRADICTION_NOT_UNKNOWN")


def wait_capture_schema(owner_token, binding):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            value = attestor.request("http://127.0.0.1:8082", f"/data/source-bindings/{binding}/capture?schemaRevision=1",
                                     "Authorization", "Bearer " + owner_token)
            if value["source"]["sourceActive"]:
                return value
        except RuntimeError:
            pass
        time.sleep(2)
    raise RuntimeError("SOURCE_SCHEMA_NOT_READY")


if __name__ == "__main__":
    failure = None
    try:
        run()
    except Exception as error:
        failure = error
    finally:
        if CAPTURE_RECONFIGURED:
            try:
                restore_capture()
            except Exception as error:
                failure = error if failure is None else RuntimeError(str(failure) + "; " + str(error))
    if failure is not None:
        print("coverage smoke failed: " + str(failure), file=sys.stderr)
        sys.exit(1)
