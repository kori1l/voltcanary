"""Opt-in real Compose owner APIs; isolated synthetic fixtures, no model calls or rule activation."""
import argparse
import base64
import copy
from datetime import datetime, timedelta, timezone
import json
import hashlib
import hmac
import os
from pathlib import Path
import secrets
import subprocess
import time
from urllib.request import urlopen
import uuid
import urllib.error
import urllib.request

from ai_dashboard_eval.api import ApiError, Client, ROOT, write_json


def ensure(condition, code):
    if not condition:
        raise ApiError(0, code)


def wait_for_services(gateway, timeout, extra_services=()):
    deadline = time.monotonic() + timeout
    pending = {"gateway": gateway + "/actuator/health", **{
        name: f"http://127.0.0.1:{port}/actuator/health"
        for name, port in (("identity", 8081), ("device", 8082), ("telemetry", 8084),
                           ("analytics", 8085), ("alert", 8086), *extra_services)}}
    while pending and time.monotonic() < deadline:
        for name, url in list(pending.items()):
            try:
                with urlopen(url, timeout=2) as response:
                    if response.status == 200:
                        del pending[name]
            except (OSError, ValueError):
                pass
        if pending:
            time.sleep(2)
    ensure(not pending, "OWNER_STACK_NOT_READY")


def apply_review(client, base, prepared, key):
    path = base + "/" + prepared["reviewId"]
    result = client.call("POST", path + "/apply", {
        "reviewRevision": prepared["revision"], "reviewDigest": prepared["reviewDigest"], "idempotencyKey": key})
    deadline = time.monotonic() + 45
    while result["status"] in ("APPLYING", "UNKNOWN") and time.monotonic() < deadline:
        result = client.call("POST", path + "/reconcile", {})
        if result["status"] in ("APPLYING", "UNKNOWN"):
            time.sleep(1)
    ensure(result["status"] == "SUCCEEDED", "OWNER_REVIEW_NOT_SUCCEEDED")
    return result


def reference_dataset(client, run_id, start="2026-11-03T00:00:00Z", end="2026-11-04T00:00:00Z", subject_key="cabinet-A"):
    key = run_id + "-table"
    reservation = client.call("POST", "/data/review-reservations", {"kind": "COLLECTION", "fieldKeys": ["subject", "flag", "effective"], "idempotencyKey": key})
    native = reservation["fieldIdsByKey"]
    collection = {"language": "VCL/1", "kind": "COLLECTION", "spec": {
        "id": reservation["resourceId"], "revision": 1, "label": "Synthetic cabinet reference",
        "fields": [{"id": native["subject"], "label": "Cabinet", "type": "TEXT", "maxLength": 120, "nullable": False},
                   {"id": native["flag"], "label": "Requires inspection", "type": "BOOLEAN", "nullable": True},
                   {"id": native["effective"], "label": "Effective interval", "type": "INTERVAL", "nullable": False}],
        "keyFieldIds": [native["subject"]], "storage": {"mode": "NATIVE", "indexedFieldIds": [native["subject"]], "history": "NONE"}}}
    prepared = client.call("POST", "/data/dataset-reviews", {"reservationId": reservation["reviewId"], "document": collection})
    apply_review(client, "/data/reviews", prepared, key)
    dataset_id = reservation["resourceId"]
    dataset = client.call("GET", f"/data/datasets/{dataset_id}?schemaRevision=1")
    for i, (subject, flag) in enumerate(((subject_key, True), ("cabinet-B", False))):
        row_key = run_id + "-row-" + str(i)
        target = {"datasetId": dataset_id, "schemaRevision": 1}
        reserved = client.call("POST", "/data/review-reservations", {"kind": "MUTATION", "target": target, "idempotencyKey": row_key})
        mutation = {"language": "VCL/1", "kind": "MUTATION", "spec": {
            "mutationId": reserved["mutationId"], "reviewId": reserved["reviewId"], "idempotencyKey": row_key,
            "target": target, "operation": "INSERT", "key": [{"fieldId": native["subject"], "literal": {"type": "TEXT", "value": subject}}],
            "values": [{"fieldId": native["flag"], "literal": {"type": "BOOLEAN", "value": flag}},
                       {"fieldId": native["effective"], "literal": {"type": "INTERVAL", "value": {
                           "start": start, "end": end}}}], "expectedRowVersion": None,
            "targetPrecondition": {"mode": "NATIVE", "generation": dataset["generation"], "dataVersion": dataset["dataVersion"]}}}
        preview = client.call("POST", f"/data/datasets/{dataset_id}/mutation-reviews", {"reservationId": reserved["reviewId"], "document": mutation})
        apply_review(client, "/data/reviews", preview, row_key)
        dataset = client.call("GET", f"/data/datasets/{dataset_id}?schemaRevision=1")
    query = {"language": "VCL/1", "kind": "QUERY", "spec": {"target": {"datasetId": dataset_id, "schemaRevision": 1},
        "select": [native["subject"], native["flag"], native["effective"]], "where": {"op": "CONST", "literal": {"type": "BOOLEAN", "value": True}},
        "order": [{"fieldId": dataset["definition"]["systemFields"]["recordIdFieldId"], "direction": "ASC"}],
        "limit": 10, "cursor": None, "read": {"mode": "LIVE"}}}
    return dataset_id, native, query


def rule(rule_id, grant_id, policy_id, binding_id, subject, flag):
    return {"language": "VCL/1", "kind": "RULE", "spec": {
        "ruleId": rule_id, "revision": 1, "name": "Synthetic door-open duration", "executionGrantId": grant_id,
        "coverage": {"maxGapUs": "2000000", "onUnknown": "UNKNOWN"},
        "decimalPolicy": {"precision": 38, "scale": 6, "rounding": "REJECT"},
        "nodes": [
            {"id": "source", "method": "read_stream", "inputs": {}, "config": {"bindingId": binding_id, "schemaRevision": 1}},
            {"id": "duration", "method": "sustained", "inputs": {"events": "source"}, "config": {
                "subjectFieldId": subject, "predicate": {"op": "FIELD", "fieldId": flag},
                "minimumDurationUs": "1000000", "maxGapUs": "2000000"}},
            {"id": "alert", "method": "emit_alert", "inputs": {"decisions": "duration"}, "config": {
                "severity": "WARNING", "dedup": "RULE_SUBJECT_OCCURRENCE", "evidencePolicyId": policy_id}}],
        "terminal": "alert", "timing": {"allowedLatenessUs": "1000000", "maxFutureSkewUs": "1000000", "idleTimeoutUs": "5000000"}}}


def docker(args, payload=None, timeout=180):
    result = subprocess.run(["docker", "compose", "exec", "-T", "analytics-service", *args], cwd=ROOT,
                            input=payload, capture_output=True, timeout=timeout)
    if result.returncode != 0:
        for line in reversed(result.stdout.decode(errors="replace").splitlines()):
            try:
                failure = json.loads(line)
            except ValueError:
                continue
            code = failure.get("code") if isinstance(failure, dict) else None
            if isinstance(code, str) and code.replace("_", "").isalnum():
                raise ApiError(0, "OWNER_HELPER_" + code)
        raise ApiError(0, "OWNER_SMOKE_HELPER_FAILED")
    return result.stdout


def machine_request(key, actor, account, rule_id, deployment_epoch, grant_id, target, operation, path,
                    method="GET", body=None, port=8086):
    now = int(time.time())
    payload = b"" if body is None else json.dumps(body, separators=(",", ":")).encode()
    digest = hashlib.sha256((method + "\n" + path + "\n" + hashlib.sha256(payload).hexdigest()).encode()).hexdigest()
    claims = {"iss": "voltcanary-analytics-worker", "aud": "device-service" if port == 8082 else "alert-service", "jti": str(uuid.uuid4()),
              "typ": "vcl-worker-read-1", "iat": now, "exp": now + 30,
              "actor": actor, "account": account, "rule": rule_id, "epoch": deployment_epoch,
              "grant": grant_id, "target": target, "operation": operation, "requestDigest": digest}
    encode = lambda value: base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")
    signing = encode({"alg": "HS256", "kid": "worker-1"}) + b"." + encode(claims)
    token = signing + b"." + base64.urlsafe_b64encode(hmac.new(key, signing, hashlib.sha256).digest()).rstrip(b"=")
    request = urllib.request.Request(f"http://127.0.0.1:{port}" + path, data=payload if method == "POST" else None,
                                     method=method, headers={"X-VCL-Worker-Assertion": token.decode(),
                                                             "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read(1_048_576))
    except urllib.error.HTTPError as failure:
        return failure.code, None


def machine_get(key, actor, account, rule_id, deployment_epoch, grant_id, target, operation, path):
    return machine_request(key, actor, account, rule_id, deployment_epoch, grant_id, target, operation, path)


def run(args):
    wait_for_services(args.gateway, args.timeout)
    print("Owner stack ready; creating isolated synthetic fixtures", flush=True)
    run_id = uuid.uuid4().hex
    password = "SmokeA9!" + secrets.token_hex(20)
    email = "owner-smoke-" + run_id + "@example.test"
    anonymous = Client(args.gateway, "", timeout=30)
    actor = anonymous.call("POST", "/auth/register", {"email": email, "password": password})["userId"]
    login = anonymous.call("POST", "/auth/login", {"email": email, "password": password})
    client = Client(args.gateway, login["accessToken"], timeout=30, refresh_token=login.get("refreshToken"))
    account = client.call("GET", "/accounts/current")["id"]
    device_types = client.call("GET", "/device-types")
    ensure(bool(device_types), "DEVICE_TYPES_UNAVAILABLE")
    device_type = next((item["code"] for item in device_types if "refriger" in item["code"].lower()), device_types[0]["code"])
    device = client.call("POST", "/devices", {"name": "Owner smoke refrigerated cabinet", "type": device_type, "metadata": {}},
                         headers={"Idempotency-Key": run_id + "-device"})
    fields = [{"path": "subject", "key": "subject", "label": "Cabinet", "type": "string", "unit": "1", "store": True, "display": True},
              {"path": "doorOpen", "key": "doorOpen", "label": "Door open", "type": "boolean", "unit": "1", "store": True, "display": True}]
    source = client.call("POST", f"/devices/{device['id']}/data-sources", {
        "sourceType": "CUSTOM", "protocol": "REST", "externalId": "owner-smoke-" + run_id,
        "mapping": {"telemetryPolicy": {"fields": fields}}, "adapterConfig": {}, "credentialReferences": {}})
    binding = client.call("GET", f"/data/source-bindings/{source['id']}?schemaRevision=1")
    source_fields = {item["sourceKey"]: item["fieldId"] for item in binding["definition"]["fieldMappings"]}
    dataset_id, native, query = reference_dataset(client, run_id)
    rows = client.call("POST", f"/data/datasets/{dataset_id}/query", {"document": query})
    ensure(len(rows["rows"]) == 2 and rows["completeness"] == "COMPLETE", "NATIVE_ROWS_NOT_VERIFIED")
    actual_rows = {row["values"][native["subject"]]["value"]: row["values"][native["flag"]]["value"] for row in rows["rows"]}
    ensure(actual_rows == {"cabinet-A": True, "cabinet-B": False}, "NATIVE_ROW_VALUES_CHANGED")
    rule_reservation = client.call("POST", "/alert-rules/configurable/review-reservations", {"kind": "RULE", "outputKeys": [], "idempotencyKey": run_id + "-rule"})
    grant_key = run_id + "-grant"
    grant_review = client.call("POST", "/alert-rules/configurable/grant-review-reservations", {
        "kind": "AUTOMATION_GRANT", "ruleReviewId": rule_reservation["reviewId"], "idempotencyKey": grant_key})
    expires = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(timespec="microseconds").replace("+00:00", "Z")
    grant_preview = client.call("POST", "/alert-rules/configurable/grant-reviews", {"reservationId": grant_review["reviewId"], "definition": {
        "sourceBindings": [{"id": source["id"], "schemaRevision": 1, "fieldIds": list(source_fields.values())}],
        "datasets": [{"id": dataset_id, "schemaRevision": 1, "fieldIds": list(native.values())}], "expiresAt": expires}})
    apply_review(client, "/alert-rules/configurable/grant-reviews", grant_preview, grant_key)
    grant_id = grant_review["grantId"]
    policy_key = run_id + "-policy"
    policy_review = client.call("POST", "/alert-rules/configurable/evidence-policy-reservations", {
        "kind": "EVIDENCE_POLICY", "grantId": grant_id, "expectedGrantEpoch": "1", "idempotencyKey": policy_key})
    policy_preview = client.call("POST", "/alert-rules/configurable/evidence-policy-reviews", {"reservationId": policy_review["reviewId"],
        "definition": {"mode": "PRIVATE_REFERENCES", "retentionDays": 1, "maxObservationReferences": 20}})
    apply_review(client, "/alert-rules/configurable/evidence-policy-reviews", policy_preview, policy_key)
    configured_key = os.environ.get("APP_VCL_WORKER_HMAC_KEY", "")
    worker_key = base64.b64decode(configured_key) if configured_key else None
    deployment_epoch = str(uuid.uuid4())
    if worker_key:
        snapshot = client.call("POST", f"/data/datasets/{dataset_id}/query-asof-snapshots", {
            "document": query, "intervalFieldId": native["effective"], "keyFieldIds": [native["subject"]]})
        sealed_query = copy.deepcopy(query)
        sealed_query["spec"]["read"] = {"mode": "SNAPSHOT", "snapshotId": snapshot["snapshotId"]}
        base_path = f"/internal/vcl/datasets/{dataset_id}"
        def row_read(route, operation, payload):
            return machine_request(worker_key, actor, account, rule_reservation["ruleId"], deployment_epoch,
                                   grant_id, dataset_id, operation, base_path + "/" + route,
                                   "POST", payload, 8082)
        manifest_body = {"document": sealed_query, "intervalFieldId": native["effective"],
                         "keyFieldIds": [native["subject"]]}
        manifest_status, manifest = row_read("query-asof-manifest", "DATASET_ASOF_MANIFEST", manifest_body)
        ensure(manifest_status == 200 and manifest["snapshotId"] == snapshot["snapshotId"],
               "MACHINE_ASOF_MANIFEST_INVALID")
        key_fields = [{"fieldId": native["subject"], "literal": {"type": "TEXT", "value": "cabinet-A"}}]
        point_body = {"document": sealed_query, "intervalFieldId": native["effective"],
                      "eventTime": "2026-11-03T12:00:00Z", "keys": key_fields}
        point_status, point = row_read("query-asof", "DATASET_ASOF", point_body)
        ensure(point_status == 200 and point["status"] == "KNOWN" and point["readManifest"] == manifest,
               "MACHINE_ASOF_POINT_INVALID")
        split_body = {"document": sealed_query, "intervalFieldId": native["effective"],
                      "start": "2026-11-02T12:00:00Z", "end": "2026-11-04T12:00:00Z", "keys": key_fields}
        split_status, split = row_read("query-asof-split", "DATASET_ASOF_SPLIT", split_body)
        ensure(split_status == 200 and [part["status"] for part in split["segments"]] ==
               ["UNKNOWN", "KNOWN", "UNKNOWN"] and split["readManifest"] == manifest,
               "MACHINE_ASOF_SPLIT_INVALID")
        ensure(row_read("query-asof", "DATASET_ASOF_MANIFEST", point_body)[0] == 401,
               "MACHINE_ASOF_SCOPE_NOT_ENFORCED")
        grant_path = f"/internal/vcl/grants/{grant_id}?grantEpoch=1"
        grant_status, grant_value = machine_get(worker_key, actor, account, rule_reservation["ruleId"],
                                               deployment_epoch, grant_id, grant_id, "GRANT_DESCRIPTION", grant_path)
        ensure(grant_status == 200 and grant_value["status"] == "ACTIVE", "MACHINE_GRANT_NOT_ACTIVE")
        policy_path = f"/internal/vcl/evidence-policies/{policy_review['policyId']}?revision=1"
        policy_status, policy_value = machine_get(worker_key, actor, account, rule_reservation["ruleId"],
                                                 deployment_epoch, grant_id, policy_review["policyId"],
                                                 "EVIDENCE_POLICY", policy_path)
        ensure(policy_status == 200 and policy_value["usable"], "MACHINE_POLICY_NOT_USABLE")
        assert machine_get(worker_key, actor, account, rule_reservation["ruleId"], deployment_epoch,
                           grant_id, grant_id, "GRANT_DESCRIPTION", grant_path + "&changed=1")[0] == 401
    document = rule(rule_reservation["ruleId"], grant_id, policy_review["policyId"], source["id"], source_fields["subject"], source_fields["doorOpen"])
    try:
        client.call("POST", "/analytics/configurable-rules/compilations", {"document": document, "readSet": {}})
    except ApiError as failure:
        ensure(failure.status == 400 and failure.code == "RULE_COMPILATION_REQUEST_INVALID",
               "RULE_COMPILATION_ROUTE_REJECTED_UNEXPECTEDLY")
    else:
        raise ApiError(0, "RULE_COMPILATION_ACCEPTED_EMPTY_READ_SET")
    lookup_document = copy.deepcopy(document)
    lookup_document["spec"]["nodes"][1]["inputs"]["events"] = "reference"
    lookup_document["spec"]["nodes"].append({"id": "reference", "method": "lookup_asof", "inputs": {"events": "source"}, "config": {
        "target": {"datasetId": dataset_id, "schemaRevision": 1}, "keyPairs": [{"eventFieldId": source_fields["subject"], "recordFieldId": native["subject"]}],
        "select": [native["flag"]], "cardinality": "EXACT_ONE", "temporal": {"mode": "EVENT_TIME"}, "recordedAt": "ACCEPTANCE_CHECKPOINT"}})
    print("Reviewed native rows, grant and evidence policy ready; checking real owner composition", flush=True)
    docker(["sh", "-c", "cat > /tmp/ConfigurableOwnerSmoke.java"], (ROOT / "scripts/ConfigurableOwnerSmoke.java").read_bytes())
    docker(["mvn", "-q", "-f", "services/analytics-service/pom.xml", "dependency:build-classpath", "-Dmdep.outputFile=/tmp/vcl-owner-smoke.cp"])
    payload = json.dumps({"accessToken": client.token, "actorId": actor, "accountId": account, "grantId": grant_id,
                          "document": document, "lookupDocument": lookup_document}).encode()
    output = docker(["sh", "-c", 'java -cp "services/analytics-service/target/classes:$(cat /tmp/vcl-owner-smoke.cp)" /tmp/ConfigurableOwnerSmoke.java'], payload)
    result = next((json.loads(line) for line in reversed(output.decode().splitlines()) if line.startswith('{"')), None)
    ensure(result is not None and result.get("status") == "PASS", "OWNER_COMPOSITION_NOT_VERIFIED")
    permission = client.call("GET", f"/alert-rules/configurable/grants/{grant_id}")
    ensure(permission["status"] == "REVOKED" and permission["epoch"] == "2", "GRANT_REVOKE_NOT_VERIFIED")
    if worker_key:
        ensure(machine_get(worker_key, actor, account, rule_reservation["ruleId"], deployment_epoch,
                           grant_id, grant_id, "GRANT_DESCRIPTION", grant_path)[0] == 409,
               "REVOKED_MACHINE_GRANT_VISIBLE")
    return {**result, "fixtureRunId": run_id, "nativeRowsVerified": 2, "inputData": "SYNTHETIC", "liveModelCalls": 0,
            "compilationRejectsEmptyReadSetThroughGateway": True,
            "ruleActivated": False, "machineGrantVerified": bool(worker_key),
            "machinePolicyVerified": bool(worker_key), "machineRevocationVerified": bool(worker_key),
            "machineAsOfVerified": bool(worker_key),
            "fixtureAccountId": account, "fixtureDeviceId": device["id"], "fixtureDatasetId": dataset_id}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--summary", type=Path, default=ROOT / "artifacts/configurable-owner-smoke-summary.json")
    args = parser.parse_args()
    try:
        summary = run(args)
    except (ApiError, subprocess.TimeoutExpired, OSError, KeyError, ValueError) as failure:
        code = failure.code if isinstance(failure, ApiError) else "HELPER_TIMEOUT" if isinstance(failure, subprocess.TimeoutExpired) else "SMOKE_FIXTURE_OR_RUNTIME_INVALID"
        summary = {"status": "FAIL", "code": code}
    write_json(args.summary, summary)
    print(json.dumps(summary, ensure_ascii=False))
    raise SystemExit(0 if summary["status"] == "PASS" else 1)
