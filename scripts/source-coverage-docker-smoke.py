"""Exercise a qualified closed source prefix through real ingestion and owner APIs."""

import argparse
from contextlib import contextmanager, nullcontext
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

from ai_dashboard_eval.api import ApiError, Client, ROOT


SPEC = importlib.util.spec_from_file_location("coverage_attestor", Path(__file__).with_name("attest-source-coverage.py"))
attestor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(attestor)
CAPTURE_RECONFIGURED = False


def service_url(port):
    return f"http://127.0.0.1:{port + int(os.environ.get('VOLT_WORKBENCH_SMOKE_PORT_OFFSET', '0'))}"


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
    until_ready(service_url(8084))


def restore_capture():
    result = subprocess.run(["docker", "compose", "--profile", "app", "up", "-d", "--no-deps", "--force-recreate",
                             "telemetry-service"], cwd=ROOT, capture_output=True, timeout=180)
    require(result.returncode == 0, "TELEMETRY_RESTORE_FAILED")
    until_ready(service_url(8084))


def ingest(source_token, device, source, epoch, sequence, event_time, metrics):
    body = {"deviceId": device, "sourceId": source, "timestamp": event_time,
            "sourcePosition": {"epoch": epoch, "sequence": str(sequence)}, "metrics": metrics}
    call = urllib.request.Request(service_url(8083) + "/ingestion/readings",
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
            value = attestor.request(service_url(8082), f"/data/source-bindings/{binding}/capture?schemaRevision=1",
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
        result = attestor.advance(statement, digest, state, service_url(8082), service_url(8084),
                                  owner_token, attestor_token)
        if result == desired:
            return
        time.sleep(2)
    raise RuntimeError("COVERAGE_STATUS_TIMEOUT")


def run(execute_zone, verify_alerts, competing_workers, execute_interval=False, execute_asof=False, verify_workbench=False):
    for port in (8080, 8081, 8082, 8083, 8084, 8085, 8086):
        until_ready(service_url(port))
    if verify_workbench:
        until_ready(service_url(8089))
    if execute_zone or execute_interval:
        check = 'test "$APP_VCL_WORKER_ENABLED" = true && test -n "$APP_VCL_WORKER_HMAC_KEY"'
        if verify_alerts:
            check += ' && test "$APP_VCL_WORKER_ALERT_EFFECTS_ENABLED" = true'
        worker = subprocess.run(["docker", "compose", "exec", "-T", "analytics-service", "sh", "-c", check],
                                cwd=ROOT, capture_output=True, timeout=20)
        require(worker.returncode == 0, "ZONE_WORKER_NOT_CONFIGURED")
    anonymous = Client(service_url(8080), "", timeout=30)
    suffix = uuid.uuid4().hex
    email = "coverage-smoke-" + suffix + "@example.test"
    password = "Coverage!" + secrets.token_hex(18)
    anonymous.call("POST", "/auth/register", {"email": email, "password": password})
    login = anonymous.call("POST", "/auth/login", {"email": email, "password": password})
    client = Client(service_url(8080), login["accessToken"], timeout=30,
                    refresh_token=login.get("refreshToken"))
    account = client.call("GET", "/accounts/current")["id"]
    device_type = client.call("GET", "/device-types")[0]["code"]
    device = client.call("POST", "/devices", {"name": "Coverage smoke source", "type": device_type, "metadata": {}},
                         headers={"Idempotency-Key": suffix + "-device"})
    fields = [{"path": "subject", "key": "subject", "label": "Subject", "type": "string", "unit": "1", "store": True, "display": True},
              {"path": "occurrence", "key": "occurrence", "label": "Occurrence", "type": "string", "unit": "1", "store": True, "display": True},
              {"path": "nextStop", "key": "nextStop", "label": "Next stop", "type": "string", "unit": "1", "store": True, "display": False},
              {"path": "doorOpen", "key": "doorOpen", "label": "Door open", "type": "boolean", "unit": "1", "store": True, "display": True}]
    for key, kind in (("position", "point"), ("zone", "spatial_ref")):
        fields.append({"path": key, "key": key, "label": key, "type": kind, "unit": "1", "store": True, "display": False})
    if execute_interval:
        for key, kind in (("start", "instant"), ("end", "instant"), ("work", "boolean"), ("vehicle", "string")):
            fields.append({"path": key, "key": key, "label": key, "type": kind, "unit": "1", "store": True, "display": False})
    zone = client.call("PUT", f"/data/spatial-features/{uuid.uuid4()}/circle", {
        "expectedRevision": 0, "geometry": {"longitude": 24, "latitude": 60, "radiusMetres": "100"}})
    routing = {"version": "direct-occurrence-1", "subjectKey": "subject", "occurrenceKey": "occurrence"}
    mapping = {"telemetryPolicy": {"fields": fields}, "occurrenceRouting": routing,
               "sampleHold": {"version": "bounded-sample-hold-1", "maxHoldUs": "2000000"}}
    if execute_interval:
        mapping["intervalReport"] = {"version": "interval-end-event-time-1", "startKey": "start",
                                     "endKey": "end", "maxSpanUs": "10000000"}
    source = client.call("POST", f"/devices/{device['id']}/data-sources", {
        "sourceType": "CUSTOM", "protocol": "REST", "externalId": "coverage-smoke-" + suffix,
        "mapping": mapping,
        "adapterConfig": {}, "credentialReferences": {}})
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
    if execute_interval:
        end = now.replace(hour=0, minute=0, second=0) - timedelta(days=1)
        start = end - timedelta(seconds=45)
    event_times = [start + timedelta(seconds=i) for i in (1, 2, 3)]
    metrics = {"subject": "cabinet", "occurrence": "trip-1", "doorOpen": True,
               "position": {"longitude": 24, "latitude": 60}, "zone": zone["id"]}
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
    initial_packets = (1, 2, 3) if execute_zone or execute_interval else (1, 3)
    for sequence in initial_packets:
        packet = metrics
        if execute_interval:
            at = event_times[sequence - 1]
            packet = {**metrics, "start": iso(at - timedelta(seconds=1)), "end": iso(at), "work": True}
        ingest(source_token, device["id"], source["externalId"], epoch, sequence,
               producer_log[sequence - 1]["eventTime"], packet)
    client.prepare_planning_session()
    wait_capture(client.token, source["id"])
    if not (execute_zone or execute_interval):
        require(attestor.advance(statement, digest, state, service_url(8082), service_url(8084),
                                 client.token, attestor_token) == "UNKNOWN", "GAP_NOT_UNKNOWN")
        restart_capture(source["id"], attestor_token)
        client.prepare_planning_session()
        require(attestor.advance(statement, digest, state, service_url(8082), service_url(8084),
                                 client.token, attestor_token) == "UNKNOWN", "RESTART_LOST_GAP")
        ingest(source_token, device["id"], source["externalId"], epoch, 2, producer_log[1]["eventTime"], metrics)
    wait_status(statement, digest, state, client.token, attestor_token, "COVERED")
    capture = wait_capture(client.token, source["id"])["capture"]
    qualified_proof = str(uuid.uuid4())
    attestor.request(service_url(8084), "/internal/observations/source-coverage",
                     "X-Coverage-Attestor-Token", attestor_token, {
                         "pin": capture["pin"], "proofId": qualified_proof,
                         "inputGeneration": capture["inputGeneration"], "producerEpoch": epoch,
                         "throughSequence": "3", "from": statement["from"], "until": statement["until"],
                         "eventTimeBasis": "SOURCE_EVENT_TIME_V1"})
    if execute_interval:
        compilation = compile_rule(client, account, source["id"], definition, qualified_proof, statement,
                                   suffix + "-interval", interval=True, execute=True, as_of=execute_asof,
                                   retention_days=2 if verify_workbench else 1)
        verify_interval_execution(client, account, source, device, source_token, epoch, compilation, end,
                                  attestor_token, verify_workbench)
        return
    if execute_zone:
        session = compile_rule(client, account, source["id"], definition, qualified_proof, statement, suffix + "-zone",
                               circle=zone, execute=True)
        peer = analytics_peer(verify_alerts) if competing_workers else nullcontext()
        with peer as competing:
            verify_zone_execution(client, account, source, device, source_token, epoch, zone, session, start,
                                  attestor_token, verify_alerts, competing)
        return
    bundle = compile_rule(client, account, source["id"], definition, qualified_proof, statement, suffix)
    window = compile_rule(client, account, source["id"], definition, qualified_proof, statement, suffix + "-window",
                          window=True)
    session = compile_rule(client, account, source["id"], definition, qualified_proof, statement, suffix + "-zone",
                           circle=zone)
    lookup = compile_rule(client, account, source["id"], definition, qualified_proof, statement, suffix + "-lookup",
                          window=True, as_of=True)
    ingest(source_token, device["id"], source["externalId"], epoch, 4, producer_log[0]["eventTime"],
           {**metrics, "occurrence": ""})
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        proof = attestor.request(service_url(8082), f"/data/source-bindings/{source['id']}/coverage/{statement['proofId']}",
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
                              "occurrenceRoutingVerified": True, "blankOccurrenceUnknown": True,
                              "compiledBundleId": bundle["bundleId"], "compiledBundleDigest": bundle["bundleDigest"],
                              "windowBundleId": window["bundleId"], "windowBundleDigest": window["bundleDigest"],
                              "zoneBundleId": session["bundleId"], "zoneBundleDigest": session["bundleDigest"],
                              "lookupBundleId": lookup["bundleId"], "lookupBundleDigest": lookup["bundleDigest"]}))
            return
        time.sleep(2)
    raise RuntimeError("LATE_CONTRADICTION_NOT_UNKNOWN")


def compile_rule(client, account, binding, definition, proof, statement, suffix, *, window=False, circle=None,
                 as_of=False, execute=False, interval=False, retention_days=1):
    spec = importlib.util.spec_from_file_location("owner_smoke", Path(__file__).with_name("configurable-owner-docker-smoke.py"))
    owner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(owner)
    datasets = []
    reference = None
    if as_of:
        reference_start, reference_end = statement["from"], statement["until"]
        if interval:
            boundary = datetime.fromisoformat(reference_end.replace("Z", "+00:00"))
            reference_end = iso(boundary + timedelta(days=1))
        dataset, native, query = owner.reference_dataset(
            client, suffix, reference_start, reference_end, "employee-1" if interval else "cabinet")
        datasets.append({"id": dataset, "schemaRevision": 1, "fieldIds": list(native.values())})
        snapshot = client.call("POST", f"/data/datasets/{dataset}/query-asof-snapshots", {
            "document": query, "intervalFieldId": native["effective"], "keyFieldIds": [native["subject"]]})
        query["spec"]["read"] = {"mode": "SNAPSHOT", "snapshotId": snapshot["snapshotId"]}
        reference = (owner, dataset, native, query, suffix)
    base = "/alert-rules/configurable"
    rule = client.call("POST", base + "/review-reservations",
                       {"kind": "RULE", "outputKeys": [], "idempotencyKey": suffix + "-rule"})
    grant_key = suffix + "-grant"
    grant = client.call("POST", base + "/grant-review-reservations",
                        {"kind": "AUTOMATION_GRANT", "ruleReviewId": rule["reviewId"], "idempotencyKey": grant_key})
    fields = {item["sourceKey"]: item["fieldId"] for item in definition["fieldMappings"]}
    expires = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(timespec="microseconds").replace("+00:00", "Z")
    review = client.call("POST", base + "/grant-reviews", {"reservationId": grant["reviewId"], "definition": {
        "sourceBindings": [{"id": binding, "schemaRevision": 1, "fieldIds": list(fields.values())}],
        "datasets": datasets, "expiresAt": expires}})
    owner.apply_review(client, base + "/grant-reviews", review, grant_key)
    policy_key = suffix + "-policy"
    policy = client.call("POST", base + "/evidence-policy-reservations", {
        "kind": "EVIDENCE_POLICY", "grantId": grant["grantId"], "expectedGrantEpoch": "1", "idempotencyKey": policy_key})
    review = client.call("POST", base + "/evidence-policy-reviews", {"reservationId": policy["reviewId"],
        "definition": {"mode": "PRIVATE_REFERENCES", "retentionDays": retention_days, "maxObservationReferences": 20}})
    owner.apply_review(client, base + "/evidence-policy-reviews", review, policy_key)
    document = owner.rule(rule["ruleId"], grant["grantId"], policy["policyId"], binding, fields["subject"], fields["doorOpen"])
    if window:
        measure = str(uuid.uuid4())
        nodes = document["spec"]["nodes"]
        nodes[1:2] = [
            {"id": "count", "method": "window_aggregate", "inputs": {"events": "source"}, "config": {
                "subjectFieldId": fields["subject"], "reducer": "COUNT", "measureFieldId": measure,
                "emit": "ON_CLOSE", "valueFieldId": None,
                "window": {"kind": "ROLLING", "sizeUs": "5000000", "stepUs": "2000000",
                           "alignment": "1970-01-01T00:00:00Z"}}},
            {"id": "condition", "method": "compare", "inputs": {"measures": "count"}, "config": {
                "predicate": {"op": "CALL", "method": "gt", "args": [
                    {"op": "FIELD", "fieldId": measure},
                    {"op": "CONST", "literal": {"type": "INT64", "value": "0"}}]}}}]
        nodes[-1]["inputs"]["decisions"] = "condition"
    if interval:
        measure = str(uuid.uuid4())
        nodes = document["spec"]["nodes"]
        work = {"op": "FIELD", "fieldId": fields["work"]}
        include = work
        if as_of:
            include = {"op": "CALL", "method": "and", "args": [work, {"op": "FIELD", "fieldId": native["flag"]}]}
        nodes[1:2] = [
            {"id": "work_duration", "method": "interval_union_window", "inputs": {"events": "source"}, "config": {
                "subjectFieldId": fields["subject"], "startFieldId": fields["start"], "endFieldId": fields["end"],
                "timezone": "UTC", "window": "LOCAL_DAY", "emit": "ON_CLOSE", "measureFieldId": measure,
                "includeWhere": include, "excludeWhere": {"op": "CALL", "method": "not", "args": [work]}}},
            {"id": "condition", "method": "compare", "inputs": {"measures": "work_duration"}, "config": {
                "predicate": {"op": "CALL", "method": "gt", "args": [
                    {"op": "FIELD", "fieldId": measure},
                    {"op": "CONST", "literal": {"type": "DURATION", "value": "3000000"}}]}}}]
        nodes[-1]["inputs"]["decisions"] = "condition"
        document["spec"]["timing"]["idleTimeoutUs"] = "120000000"
    read_set = {
        "account": account, "binding": binding, "schemaRevision": 1, "proofId": proof,
        "from": statement["from"], "boundary": statement["until"]}
    preview_lookups = {}
    if as_of:
        nodes = document["spec"]["nodes"]
        nodes[1]["inputs"]["events"] = "reference"
        nodes.insert(1, {"id": "reference", "method": "lookup_asof", "inputs": {"events": "source"}, "config": {
            "target": query["spec"]["target"], "keyPairs": [
                {"eventFieldId": fields["subject"], "recordFieldId": native["subject"]}],
            "select": [native["flag"]], "cardinality": "EXACT_ONE", "temporal": {"mode": "EVENT_TIME"},
            "recordedAt": "ACCEPTANCE_CHECKPOINT"}})
        read_set["lookups"] = {"reference": {"query": query, "intervalField": native["effective"]}}
        preview_lookups = {"reference": {"query": json.dumps(query), "intervalField": native["effective"]}}
    if circle:
        read_set["circles"] = [{"id": circle["id"], "revision": circle["revision"]}]
        nodes = document["spec"]["nodes"]
        nodes[1:2] = [
            {"id": "visit", "method": "zone_session", "inputs": {"events": "source"}, "config": {
                "subjectFieldId": fields["subject"], "occurrenceFieldId": fields["occurrence"],
                "zoneFieldId": fields["zone"], "positionFieldId": fields["position"], "accuracyFieldId": None,
                "uncertaintyFloorMetres": 5, "exitDebounceUs": "1000000", "maxDurationUs": "10000000",
                "minimumExitObservations": 2, "accuracyFallback": "APPROVED_FLOOR"}},
            {"id": "witness", "method": "observed_within", "inputs": {"sessions": "visit"}, "config": {
                "predicate": {"op": "FIELD", "fieldId": fields["doorOpen"]},
                "minimumDurationUs": "0" if execute else "1000000"}},
            {"id": "absence", "method": "absent_within", "inputs": {"sessions": "visit", "witnesses": "witness"},
             "config": {"allowedLatenessUs": "1000000", "onUnknown": "UNKNOWN"}}]
        nodes[-1]["inputs"]["decisions"] = "absence"
        if execute:
            document["spec"]["timing"]["idleTimeoutUs"] = "120000000"
    profile = "zone" if circle else "interval" if interval else "asof" if as_of else "window" if window else "sustained"
    try:
        receipt = client.call("POST", "/analytics/configurable-rules/compilations",
                              {"document": document, "readSet": read_set})
    except ApiError as error:
        raise RuntimeError(f"{profile} compilation: {error}") from None
    try:
        checkpoint = client.call("POST", "/analytics/configurable/rule-cutovers/checkpoint", {
            "account": account, "bundle": receipt["bundleId"], "from": statement["from"], "boundary": statement["until"],
            "proofs": [{"binding": binding, "schemaRevision": 1, "proofId": proof}], "lookups": preview_lookups})
    except ApiError as error:
        raise RuntimeError(f"{profile} checkpoint: {error}") from None
    require(checkpoint == receipt["checkpoint"], "COMPILED_CHECKPOINT_CHANGED")
    if as_of:
        require(bool(checkpoint["references"]["reference"]["manifestDigest"]), "REFERENCE_MANIFEST_NOT_PINNED")
    if execute:
        return (receipt, rule["ruleId"], grant["grantId"], reference) if interval else (
            receipt, rule["ruleId"], grant["grantId"])
    return receipt


def wait_active(client, bundle, rule, grant):
    base = "/alert-rules/configurable/cutover-reviews"
    epoch = str(uuid.uuid4())
    checkpoint = bundle["checkpoint"]
    review = client.call("POST", base, {
        "ruleId": rule, "grantId": grant, "predecessorEpoch": None, "successorEpoch": epoch,
        "bundleId": bundle["bundleId"], "bundleDigest": bundle["bundleDigest"], "checkpointDigest": checkpoint["digest"]})
    review_id = review["id"]
    client.call("POST", f"{base}/{review_id}/confirm", {"reviewDigest": review["reviewDigest"]})
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        deployed = client.call("POST", f"/analytics/configurable/rule-cutovers/{review_id}/apply", checkpoint)
        if deployed["state"] == "ACTIVE":
            require(deployed["epoch"] == epoch, "ZONE_DEPLOYMENT_CHANGED")
            return epoch
        require(deployed["state"] == "ACTIVATING", "ZONE_DEPLOYMENT_NOT_ACTIVATING")
        time.sleep(2)
    raise RuntimeError("ZONE_DEPLOYMENT_NOT_ACTIVE")


def rule_measures(account, rule, epoch, *, interval=False, with_ids=False):
    account, rule, epoch = map(uuid.UUID, (account, rule, epoch))
    profile = "vcl-interval-final-measure-1" if interval else "vcl-session-absence-measure-1"
    sql = (
        "SELECT json_build_array(o.output_id, o.payload::json) "
        "FROM vcl_keyed_outbox o JOIN vcl_keyed_state s USING (account_id, key_digest) "
        f"WHERE s.account_id='{account}' AND s.rule_id='{rule}' AND s.deployment_epoch='{epoch}' "
        f"AND o.output_kind='MEASURE' AND o.payload::jsonb->>'profile'='{profile}'"
    )
    rows = [json.loads(line) for line in postgres_rows("ANALYTICS_POSTGRES_DB", "voltcanary_analytics", sql)]
    return rows if with_ids else [payload for _, payload in rows]


def postgres_rows(database_env, default_database, sql):
    database = os.environ.get(database_env, default_database)
    result = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "sh", "-c",
                             'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"',
                             "psql", database], cwd=ROOT, input=sql, text=True, capture_output=True, timeout=20)
    require(result.returncode == 0, "ZONE_DATABASE_QUERY_FAILED")
    return [line for line in result.stdout.splitlines() if line]


def iso(moment):
    return moment.isoformat().replace("+00:00", "Z")


def peer_ready(container):
    return subprocess.run(["docker", "exec", container, "curl", "-fsS", "--max-time", "3",
                           service_url(8085) + "/actuator/health"], cwd=ROOT, capture_output=True,
                          timeout=10).returncode == 0


def wait_peer_ready(container):
    deadline = time.monotonic() + 300
    while not peer_ready(container):
        state = subprocess.run(["docker", "inspect", "--format", "{{.State.Running}}", container],
                               cwd=ROOT, capture_output=True, text=True, timeout=10)
        require(state.returncode == 0 and state.stdout.strip() == "true", "ZONE_PEER_EXITED")
        require(time.monotonic() < deadline, "ZONE_PEER_NOT_READY")
        time.sleep(2)


@contextmanager
def analytics_peer(verify_alerts):
    container = "voltcanary-g4-peer-" + uuid.uuid4().hex
    command = ["docker", "compose", "--profile", "app", "run", "--no-deps", "-d", "--name", container,
               "-e", "APP_KAFKA_CONSUME_ENABLED=false", "-e", "ANALYTICS_JOBS_WORKER_ENABLED=false",
               "-e", "APP_ML_TRAINING_WORKER_ENABLED=false", "-e", "APP_VCL_WORKER_ENABLED=true",
               "-e", f"APP_VCL_WORKER_ALERT_EFFECTS_ENABLED={str(verify_alerts).lower()}",
               "analytics-service", "mvn", "-f", "services/analytics-service/pom.xml", "spring-boot:run"]
    try:
        result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=120)
        require(result.returncode == 0, "ZONE_PEER_START_FAILED")
        wait_peer_ready(container)
        fingerprint = 'test -n "$APP_VCL_WORKER_HMAC_KEY" && printf %s "$APP_VCL_WORKER_HMAC_KEY" | sha256sum'
        primary_key = subprocess.run(["docker", "compose", "exec", "-T", "analytics-service", "sh", "-c", fingerprint],
                                     cwd=ROOT, capture_output=True, timeout=10)
        peer_key = subprocess.run(["docker", "exec", container, "sh", "-c", fingerprint],
                                  cwd=ROOT, capture_output=True, timeout=10)
        require(primary_key.returncode == peer_key.returncode == 0 and primary_key.stdout == peer_key.stdout,
                "ZONE_PEER_IDENTITY_MISMATCH")
        yield container
    finally:
        subprocess.run(["docker", "rm", "-f", container], cwd=ROOT, capture_output=True, timeout=30)


@contextmanager
def recover_with_peer(container):
    stopped = subprocess.run(["docker", "compose", "stop", "analytics-service"], cwd=ROOT,
                             capture_output=True, timeout=60)
    try:
        require(stopped.returncode == 0, "ZONE_PRIMARY_STOP_FAILED")
        restarted = subprocess.run(["docker", "restart", container], cwd=ROOT, capture_output=True, timeout=60)
        require(restarted.returncode == 0, "ZONE_PEER_RESTART_FAILED")
        wait_peer_ready(container)
        yield
    finally:
        started = subprocess.run(["docker", "compose", "start", "analytics-service"], cwd=ROOT,
                                 capture_output=True, timeout=60)
        require(started.returncode == 0, "ZONE_PRIMARY_RESTORE_FAILED")
        until_ready(service_url(8085))


def attest_visit_cut(client, source, producer, sequence, start, end, token):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        capture = wait_capture(client.token, source)["capture"]
        if int(capture["headCheckpoint"]) >= sequence:
            break
        time.sleep(1)
    else:
        raise RuntimeError("ZONE_VISITS_NOT_CAPTURED")
    proof = attestor.request(service_url(8084), "/internal/observations/source-coverage",
                            "X-Coverage-Attestor-Token", token, {
                                "pin": capture["pin"], "proofId": str(uuid.uuid4()),
                                "inputGeneration": capture["inputGeneration"], "producerEpoch": producer,
                                "throughSequence": str(sequence), "from": iso(start), "until": iso(end),
                                "eventTimeBasis": "SOURCE_EVENT_TIME_V1"})
    require(proof["coverage"] == "COVERED", "ZONE_VISIT_COVERAGE_NOT_QUALIFIED")


def wait_rule_measures(account, rule, deployment, count, *, interval=False):
    deadline = time.monotonic() + 360
    while time.monotonic() < deadline:
        measures = rule_measures(account, rule, deployment, interval=interval)
        if len(measures) >= count:
            require(len(measures) == count, "ZONE_VISIT_OUTCOMES_DUPLICATED")
            return measures
        time.sleep(2)
    raise RuntimeError(f"ZONE_VISIT_OUTCOMES_TIMEOUT_EXPECTED_{count}")


def verify_interval_execution(client, account, source, device, token, producer, compilation, boundary, attestor_token,
                              verify_workbench=False):
    bundle, rule, grant, reference = compilation
    deployment = wait_active(client, bundle, rule, grant)
    at = boundary + timedelta(hours=1)
    split = boundary + timedelta(hours=12)
    end = boundary + timedelta(days=1, seconds=11)

    def report(sequence, start, stop, work, vehicle):
        begin = iso(at + timedelta(seconds=start))
        finish = iso(at + timedelta(seconds=stop))
        ingest(token, device["id"], source["externalId"], producer, sequence, finish,
               {"subject": "employee-1", "start": begin, "end": finish, "work": work, "vehicle": vehicle})

    def publish_coverage(sequence):
        attest_visit_cut(client, source["id"], producer, sequence, boundary, split, attestor_token)
        attest_visit_cut(client, source["id"], producer, sequence, split, end, attestor_token)

    reports = ((0, 2, True, "vehicle-A"), (2, 3, False, "vehicle-A"),
               (1, 4, True, "vehicle-B"), (4, 5, True, "vehicle-B"))
    for sequence, (start, stop, work, vehicle) in enumerate(reports, 4):
        report(sequence, start, stop, work, vehicle)
    publish_coverage(7)
    first, = wait_rule_measures(account, rule, deployment, 1, interval=True)
    require(first["finalized"] and first["status"] == "OK"
            and first["literal"] == {"type": "DURATION", "value": "4000000"}
            and first["decision"]["literal"]["value"] is True, "INTERVAL_INITIAL_OUTCOME_INVALID")
    if verify_workbench:
        verify_rule_dashboard(client, account, rule, deployment, bundle, first)
    alert = wait_zone_alert(client, account, device["id"], rule, 1, "OPEN")
    if reference:
        verify_interval_rekeys(account, rule, deployment)
        verify_reference_correction(client, account, rule, deployment, device["id"], first, alert, reference)
        print(json.dumps({"status": "passed", "accountId": account, "ruleId": rule, "epoch": deployment,
                          "asOfRekeysVerified": True, "historicalReferenceCorrection": True,
                          "expiredEvidenceRejected": True, "alertLifecycleVerified": True}))
        return
    report(8, 1, 3, False, "vehicle-A")
    publish_coverage(8)
    outcomes = wait_rule_measures(account, rule, deployment, 2, interval=True)
    corrections = [row for row in outcomes if row.get("supersedes")]
    require(first in outcomes and len(corrections) == 1 and corrections[0]["finalized"] and corrections[0]["status"] == "OK"
            and corrections[0]["literal"] == {"type": "DURATION", "value": "3000000"}
            and corrections[0]["decision"]["literal"]["value"] is False, "INTERVAL_CORRECTION_INVALID")
    require(wait_zone_alert(client, account, device["id"], rule, 2, "RESOLVED") == alert, "INTERVAL_ALERT_CHANGED")
    print(json.dumps({"status": "passed", "accountId": account, "ruleId": rule, "epoch": deployment,
                      "intervalUnion": True, "breakCorrection": True, "alertLifecycleVerified": True}))


def verify_rule_dashboard(client, account, rule, deployment, bundle, expected):
    measure, = [output_id for output_id, row in rule_measures(account, rule, deployment, interval=True, with_ids=True)
                if row == expected]
    base = "/analytics/configurable/rule-cutovers"
    scope = {"account": account, "epoch": deployment}
    current = client.call("POST", base + "/deployed-checkpoint", scope)
    require(current["rule"] == rule and current["compilation"] == bundle, "WORKBENCH_DEPLOYED_CHECKPOINT_CHANGED")
    listed = client.call("POST", base + "/historical-measure-list", scope)
    require(listed["items"] == [{"measure": measure, "node": expected["node"], "from": expected["windowStart"],
                                 "until": expected["windowEnd"], "status": expected["status"]}],
            "WORKBENCH_MEASURE_CATALOG_CHANGED")
    query = {"epoch": deployment, "measure": measure}
    result = client.call("POST", base + "/historical-measure", {**scope, "measure": measure})
    require(result["literal"] == expected["literal"] and result["status"] == expected["status"]
            and result["evidence"] == expected["evidence"], "WORKBENCH_MEASURE_READBACK_CHANGED")
    widget = {"id": "measure", "type": "rule-window-result", "typeVersion": 1, "title": "Saved interval result",
              "binding": {"mode": "DYNAMIC"}, "query": query, "presentation": {}, "navigation": {}, "payload": {}}
    definition = {"schemaVersion": 1, "name": "Synthetic interval rule dashboard", "description": "",
                  "defaultContext": {}, "sections": [], "widgets": [widget], "layouts": {}}
    key = uuid.uuid4().hex
    dashboard = client.call("POST", "/dashboards", {"definition": definition}, {"Idempotency-Key": key})
    path = "/dashboards/" + dashboard["id"]
    draft = client.call("GET", path + "/draft")
    parameters = {"draftEtag": draft["etag"]}
    require(client.call("POST", path + "/query-validation", parameters)["panels"][0]["status"] == "valid",
            "WORKBENCH_MEASURE_WIDGET_INVALID")
    preview = client.call("POST", path + "/query", parameters)["panels"][0]
    require(preview["status"] == "ready" and preview["data"]["ruleWindowResult"] == result,
            "WORKBENCH_MEASURE_PREVIEW_CHANGED")
    client.call("POST", path + "/publish", parameters,
                {"If-Match": '"' + draft["etag"] + '"', "Idempotency-Key": key + "-publish"})
    published = client.call("POST", path + "/query", {})["panels"][0]
    require(published["status"] == "ready" and published["data"]["ruleWindowResult"] == result,
            "WORKBENCH_MEASURE_PUBLICATION_CHANGED")
    print(json.dumps({"status": "passed", "ruleWindowDashboard": True, "checkpoint": True, "measureCatalogue": True}))


def verify_reference_correction(client, account, rule, deployment, device, first, alert, reference):
    owner, dataset, fields, query, suffix = reference
    original_id, = [output_id for output_id, payload in rule_measures(
        account, rule, deployment, interval=True, with_ids=True) if payload == first]
    metadata = client.call("GET", f"/data/datasets/{dataset}?schemaRevision=1")
    key = suffix + "-correct-reference"
    target = query["spec"]["target"]
    reserved = client.call("POST", "/data/review-reservations", {
        "kind": "MUTATION", "target": target, "idempotencyKey": key})
    mutation = {"language": "VCL/1", "kind": "MUTATION", "spec": {
        "mutationId": reserved["mutationId"], "reviewId": reserved["reviewId"], "idempotencyKey": key,
        "target": target, "operation": "UPDATE", "expectedRowVersion": "1",
        "key": [{"fieldId": fields["subject"], "literal": {"type": "TEXT", "value": "employee-1"}}],
        "values": [{"fieldId": fields["flag"], "literal": {"type": "BOOLEAN", "value": False}}],
        "targetPrecondition": {"mode": "NATIVE", "generation": metadata["generation"],
                               "dataVersion": metadata["dataVersion"]}}}
    preview = client.call("POST", f"/data/datasets/{dataset}/mutation-reviews", {
        "reservationId": reserved["reviewId"], "document": mutation})
    owner.apply_review(client, "/data/reviews", preview, key)
    live = {**query, "spec": {**query["spec"], "read": {"mode": "LIVE"}}}
    snapshot = client.call("POST", f"/data/datasets/{dataset}/query-asof-snapshots", {
        "document": live, "intervalFieldId": fields["effective"], "keyFieldIds": [fields["subject"]]})
    live["spec"]["read"] = {"mode": "SNAPSHOT", "snapshotId": snapshot["snapshotId"]}
    request = {"account": account, "epoch": deployment, "measure": original_id, "corrected": {
        "reference": {"query": json.dumps(live), "intervalField": fields["effective"]}}}
    base = "/analytics/configurable/rule-cutovers"
    review = client.call("POST", base + "/historical-window-comparison", request)
    comparison = review["comparison"]
    require(comparison["original"]["literal"] == first["literal"]
            and comparison["corrected"]["literal"] == {"type": "DURATION", "value": "0"},
            "REFERENCE_COMPARISON_INVALID")
    applied = client.call("POST", base + "/historical-window-corrections", {
        "window": request, "confirmationDigest": review["confirmationDigest"], "updateAlert": True})
    require(applied["comparison"] == comparison and bool(applied["alertProposalId"]), "REFERENCE_CORRECTION_INVALID")
    require(wait_zone_alert(client, account, device, rule, 2, "RESOLVED") == alert, "REFERENCE_CORRECTION_ALERT_CHANGED")
    outputs = dict(rule_measures(account, rule, deployment, interval=True, with_ids=True))
    require(outputs.get(original_id) == first and any(
        value.get("supersedes") == original_id and value["literal"] == comparison["corrected"]["literal"]
        for value in outputs.values()), "REFERENCE_HISTORY_CHANGED")
    original_snapshot = uuid.UUID(query["spec"]["read"]["snapshotId"])
    dataset_id, account_id = map(uuid.UUID, (dataset, account))
    expired = postgres_rows("DEVICE_POSTGRES_DB", "voltcanary_device", f"""
        UPDATE cfg_native_query_snapshot SET expires_at = CURRENT_TIMESTAMP
        WHERE snapshot_id = '{original_snapshot}' AND dataset_id = '{dataset_id}' AND account_id = '{account_id}'
        RETURNING snapshot_id
    """)
    require(expired == [str(original_snapshot)], "REFERENCE_EXPIRATION_FIXTURE_FAILED")
    try:
        client.call("POST", base + "/historical-window-comparison", request)
    except ApiError as error:
        require((error.status, error.code) == (410, "EVIDENCE_EXPIRED"), "REFERENCE_EXPIRATION_DIAGNOSTIC_INVALID")
    else:
        raise RuntimeError("REFERENCE_EXPIRED_EVIDENCE_WAS_REPLAYED")
    require(dict(rule_measures(account, rule, deployment, interval=True, with_ids=True)) == outputs,
            "REFERENCE_EXPIRATION_CHANGED_HISTORY")


def verify_interval_rekeys(account, rule, deployment):
    account, rule, deployment = map(uuid.UUID, (account, rule, deployment))
    sql = f"""
        SELECT count(*), count(r.receipt_id)
        FROM vcl_keyed_outbox o
        LEFT JOIN vcl_keyed_rekey_receipts r
          ON r.account_id = o.account_id AND r.source_key_digest = o.key_digest
         AND r.output_id = o.output_id AND r.output_digest = o.output_digest
         AND r.receipt_id = o.ack_receipt_id AND r.receipt_digest = o.ack_receipt_digest
        WHERE o.output_kind = 'REKEY' AND o.account_id = '{account}'
          AND o.target_rule_id = '{rule}' AND o.target_epoch = '{deployment}'
    """
    row, = postgres_rows("ANALYTICS_POSTGRES_DB", "voltcanary_analytics", sql)
    assignments, receipts = map(int, row.split("|"))
    require(assignments >= 4 and assignments == receipts, "INTERVAL_REKEY_RECEIPTS_INVALID")


def wait_zone_alert(client, account, device, rule, count, status):
    account = uuid.UUID(account)
    sql = (f"SELECT original_alert_id FROM cfg_rule_alert_effect_receipt WHERE account_id='{account}' "
           "AND original_alert_id IS NOT NULL")
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        ids = postgres_rows("ALERT_POSTGRES_DB", "voltcanary_alerts", sql)
        if len(ids) >= count:
            require(len(ids) == count and len(set(ids)) == 1, "ZONE_ALERT_DUPLICATED")
            alert = client.call("GET", f"/alerts/{ids[0]}")
            require(alert["deviceId"] == device and alert["ruleId"] == rule
                    and alert["alertType"] == "CONFIGURABLE_RULE" and alert["status"] == status,
                    "ZONE_ALERT_LIFECYCLE_INVALID")
            return alert["id"]
        time.sleep(2)
    raise RuntimeError("ZONE_ALERT_RECEIPT_TIMEOUT")


def verify_revoked_worker(client, account, deployment, grant, source, device, token, producer, sequence, zone):
    base = "/alert-rules/configurable"
    path = f"{base}/grants/{grant}/revoke"
    request = {"expectedEpoch": "1", "idempotencyKey": "zone-revoke-" + deployment}
    deadline = time.monotonic() + 30
    while True:
        receipt = client.call("POST", path, request)
        if receipt["status"] == "SUCCEEDED":
            require(receipt["resultEpoch"] == "2", "ZONE_GRANT_EPOCH_INVALID")
            break
        require(receipt["status"] in ("PENDING", "UNKNOWN") and time.monotonic() < deadline,
                "ZONE_GRANT_REVOKE_FAILED")
        time.sleep(1)
    occurred = datetime.now(timezone.utc)
    ingest(token, device["id"], source["externalId"], producer, sequence, iso(occurred), {
        "subject": "cabinet", "occurrence": "after-revoke", "doorOpen": False, "zone": zone["id"],
        "position": {"longitude": 24.01, "latitude": 60}})
    deadline = time.monotonic() + 120
    expected = f"VCL journal opening remains pending for epoch {deployment}: AUTOMATION_GRANT_INACTIVE"
    while time.monotonic() < deadline:
        capture = wait_capture(client.token, source["id"])["capture"]
        log = subprocess.run(["docker", "compose", "logs", "--since", iso(occurred), "analytics-service"],
                             cwd=ROOT, capture_output=True, text=True, timeout=20)
        require(log.returncode == 0, "ZONE_WORKER_LOGS_UNAVAILABLE")
        if int(capture["headCheckpoint"]) >= sequence and expected in log.stdout:
            safe_account, safe_epoch = uuid.UUID(account), uuid.UUID(deployment)
            sql = (f"SELECT count(*) FROM vcl_keyed_state WHERE account_id='{safe_account}' "
                   f"AND deployment_epoch='{safe_epoch}'")
            require(postgres_rows("ANALYTICS_POSTGRES_DB", "voltcanary_analytics", sql) == ["2"],
                    "ZONE_REVOKED_WORKER_WROTE_STATE")
            return
        time.sleep(2)
    raise RuntimeError("ZONE_REVOKED_WORKER_NOT_OBSERVED")


def verify_zone_execution(client, account, source, device, token, producer, zone, compilation, coverage_start,
                          attestor_token, verify_alerts, competing):
    bundle, rule, grant = compilation
    deployment = wait_active(client, bundle, rule, grant)
    base_time = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(seconds=1)
    through = base_time + timedelta(seconds=6)
    time.sleep(max(0, (through - datetime.now(timezone.utc)).total_seconds()))
    inside = {"longitude": 24, "latitude": 60}
    outside = {"longitude": 24.01, "latitude": 60}
    sequence = 3
    # Same subject, separate visit identities. The door-only row shares an exact GPS instant.
    for offset, position in enumerate((outside, inside, inside, outside, outside)):
        for occurrence in ("served", "missed"):
            metrics = {"subject": "cabinet", "occurrence": occurrence, "doorOpen": False, "zone": zone["id"],
                       "nextStop": "current" if offset < 2 else "next", "position": position}
            instant = iso(base_time + timedelta(seconds=offset))
            sequence += 1
            ingest(token, device["id"], source["externalId"], producer, sequence, instant, metrics)
            if offset == 2 and occurrence == "served":
                sequence += 1
                door = {key: value for key, value in metrics.items() if key != "position"}
                ingest(token, device["id"], source["externalId"], producer, sequence, instant, {**door, "doorOpen": True})
    attest_visit_cut(client, source["id"], producer, sequence, coverage_start, through, attestor_token)
    measures = wait_rule_measures(account, rule, deployment, 2)
    require(all(row["status"] == "OK" for row in measures), "ZONE_VISIT_OUTCOMES_INVALID")
    served = [row for row in measures if row["literal"]["value"] is False]
    missed = [row for row in measures if row["literal"]["value"] is True]
    require(len(served) == len(missed) == 1 and served[0].get("firstWitness")
            and len(served[0].get("positionWitnesses", ())) == 1
            and not missed[0].get("firstWitness"), "ZONE_DOOR_EVIDENCE_INVALID")
    alert = wait_zone_alert(client, account, device["id"], rule, 1, "OPEN") if verify_alerts else None
    recovery = recover_with_peer(competing) if competing else nullcontext()
    with recovery:
        require(len(rule_measures(account, rule, deployment)) == 2, "ZONE_OUTCOMES_CHANGED_AFTER_RESTART")
        sequence += 1
        ingest(token, device["id"], source["externalId"], producer, sequence, iso(base_time + timedelta(seconds=2)), {
            "subject": "cabinet", "occurrence": "missed", "doorOpen": True, "zone": zone["id"], "nextStop": "next"})
        attest_visit_cut(client, source["id"], producer, sequence, coverage_start, through, attestor_token)
        revised = wait_rule_measures(account, rule, deployment, 3)
        if verify_alerts:
            require(wait_zone_alert(client, account, device["id"], rule, 2, "RESOLVED") == alert, "ZONE_ALERT_CHANGED")
    corrected = [row for row in revised if row.get("supersedes")]
    require(len(corrected) == 1 and corrected[0]["status"] == "OK" and corrected[0]["literal"]["value"] is False
            and corrected[0].get("lateObservation") == corrected[0].get("firstWitness")
            and corrected[0].get("exitObservation") == missed[0].get("exitObservation")
            and served[0] in revised and missed[0] in revised, "ZONE_LATE_WITNESS_CORRECTION_INVALID")
    if competing:
        require(peer_ready(competing), "ZONE_PEER_NOT_READY_AFTER_EXECUTION")
        require(len(rule_measures(account, rule, deployment)) == 3, "ZONE_VISIT_OUTCOMES_DUPLICATED")
    verify_revoked_worker(client, account, deployment, grant, source, device, token, producer, sequence + 1, zone)
    print(json.dumps({"status": "passed", "accountId": account, "ruleId": rule, "epoch": deployment,
                      "separateOccurrences": True, "zeroDurationWitness": True, "coveredAbsence": True,
                      "lateWitnessCorrection": True, "alertLifecycleVerified": verify_alerts,
                      "secondWorkerHealthy": bool(competing), "recoveredWithRestartedWorker": bool(competing),
                      "revokedWorkerRefusedInput": True}))


def wait_capture_schema(owner_token, binding):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            value = attestor.request(service_url(8082), f"/data/source-bindings/{binding}/capture?schemaRevision=1",
                                     "Authorization", "Bearer " + owner_token)
            if value["source"]["sourceActive"]:
                return value
        except RuntimeError:
            pass
        time.sleep(2)
    raise RuntimeError("SOURCE_SCHEMA_NOT_READY")


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--execute-zone", action="store_true", help="Exercise reviewed zone deployment with VCL workers enabled")
    cli.add_argument("--verify-alert-effects", action="store_true", help="Verify alert creation and retraction for the zone deployment")
    cli.add_argument("--competing-workers", action="store_true", help="Run the zone scenario with a second Analytics process")
    interval = cli.add_mutually_exclusive_group()
    interval.add_argument("--execute-interval", action="store_true", help="Verify interval union, late break correction and alert delivery")
    interval.add_argument("--execute-asof", action="store_true", help="Verify interval execution with a sealed reference and durable rekey acknowledgements")
    cli.add_argument("--verify-workbench", action="store_true", help="Verify the saved interval measure in draft and published dashboards")
    args = cli.parse_args()
    execute_interval = args.execute_interval or args.execute_asof
    if args.verify_workbench and not execute_interval:
        cli.error("workbench verification requires an interval execution case")
    if execute_interval and (args.execute_zone or args.competing_workers):
        cli.error("interval execution runs independently of the zone scenario")
    failure = None
    try:
        run(not execute_interval and (args.execute_zone or args.verify_alert_effects or args.competing_workers),
            args.verify_alert_effects or execute_interval, args.competing_workers, execute_interval, args.execute_asof,
            args.verify_workbench)
    except Exception as error:
        failure = error
        print("coverage smoke failed: " + str(error), file=sys.stderr)
    finally:
        if CAPTURE_RECONFIGURED:
            try:
                restore_capture()
            except Exception as error:
                print("coverage capture restore failed: " + str(error), file=sys.stderr)
                if failure is None:
                    failure = error
    if failure is not None:
        sys.exit(1)
