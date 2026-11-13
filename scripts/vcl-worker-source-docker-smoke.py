"""Verify scoped machine owner reads against a real device and restore Compose."""

import base64
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

from ai_dashboard_eval.api import Client, ROOT


def health(timeout=900, port=8082):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health", timeout=3) as response:
                if response.status == 200:
                    return
        except OSError:
            pass
        time.sleep(2)
    raise RuntimeError("DEVICE_NOT_READY")


def restart(key=None):
    env = os.environ.copy()
    if key is not None:
        env["APP_VCL_WORKER_HMAC_KEY"] = base64.b64encode(key).decode()
    result = subprocess.run(["docker", "compose", "--profile", "app", "up", "-d", "--no-deps",
                             "--force-recreate", "device-service"], cwd=ROOT, env=env,
                            capture_output=True, timeout=180)
    if result.returncode:
        raise RuntimeError("DEVICE_RESTART_FAILED")
    health()
    gateway = subprocess.run(["docker", "compose", "restart", "api-gateway"], cwd=ROOT,
                             capture_output=True, timeout=180)
    if gateway.returncode:
        raise RuntimeError("GATEWAY_RESTART_FAILED")
    health(port=8080)


def jwt_subject(token):
    data = token.split(".")[1]
    return str(uuid.UUID(json.loads(base64.urlsafe_b64decode(data + "=" * (-len(data) % 4)))["sub"]))


def machine_token(key, actor, account, binding, method, path, operation, body=b""):
    digest = hashlib.sha256((method + "\n" + path + "\n" + hashlib.sha256(body).hexdigest()).encode()).hexdigest()
    now = int(time.time())
    claims = {"iss": "voltcanary-analytics-worker", "aud": "device-service", "jti": str(uuid.uuid4()),
              "typ": "vcl-worker-read-1", "iat": now, "exp": now + 30,
              "actor": actor, "account": account, "rule": str(uuid.uuid4()), "epoch": str(uuid.uuid4()),
              "grant": str(uuid.uuid4()), "target": binding, "operation": operation,
              "requestDigest": digest}
    header = {"alg": "HS256", "kid": "worker-1"}
    encode = lambda value: base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")
    signing_input = encode(header) + b"." + encode(claims)
    signature = base64.urlsafe_b64encode(hmac.new(key, signing_input, hashlib.sha256).digest()).rstrip(b"=")
    return (signing_input + b"." + signature).decode()


def read(binding, revision, assertion=None, bearer=None):
    headers = {}
    if assertion is not None:
        headers["X-VCL-Worker-Assertion"] = assertion
    if bearer is not None:
        headers["Authorization"] = "Bearer " + bearer
    request = urllib.request.Request(
        f"http://127.0.0.1:8082/internal/vcl/source-bindings/{binding}/capture?schemaRevision={revision}",
        headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read(1_048_576))
    except urllib.error.HTTPError as error:
        return error.code, None


def post(binding, path, body, assertion):
    request = urllib.request.Request("http://127.0.0.1:8082" + path, data=body,
                                     headers={"X-VCL-Worker-Assertion": assertion,
                                              "Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read(1_048_576))
    except urllib.error.HTTPError as error:
        return error.code, None


def capture_token(key, actor, account, binding, revision):
    path = f"/internal/vcl/source-bindings/{binding}/capture?schemaRevision={revision}"
    return machine_token(key, actor, account, binding, "GET", path, "SOURCE_CAPTURE")


def apply_review(client, prepared, key):
    path = "/data/reviews/" + prepared["reviewId"]
    result = client.call("POST", path + "/apply", {
        "reviewRevision": prepared["revision"], "reviewDigest": prepared["reviewDigest"], "idempotencyKey": key})
    until = time.monotonic() + 45
    while result["status"] in ("APPLYING", "UNKNOWN") and time.monotonic() < until:
        result = client.call("POST", path + "/reconcile", {})
        if result["status"] in ("APPLYING", "UNKNOWN"):
            time.sleep(1)
    if result["status"] != "SUCCEEDED":
        raise RuntimeError("DATASET_REVIEW_NOT_SUCCEEDED")


def get_dataset(dataset, assertion):
    request = urllib.request.Request(f"http://127.0.0.1:8082/internal/vcl/datasets/{dataset}?schemaRevision=1",
                                     headers={"X-VCL-Worker-Assertion": assertion})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read(1_048_576))
    except urllib.error.HTTPError as error:
        return error.code, None


def run():
    key = secrets.token_bytes(32)
    changed = False
    try:
        changed = True
        restart(key)
        anonymous = Client("http://127.0.0.1:8080", "", timeout=30)
        suffix = uuid.uuid4().hex
        email = "vcl-worker-" + suffix + "@example.test"
        password = "Worker!" + secrets.token_hex(18)
        anonymous.call("POST", "/auth/register", {"email": email, "password": password})
        login = anonymous.call("POST", "/auth/login", {"email": email, "password": password})
        actor = jwt_subject(login["accessToken"])
        client = Client("http://127.0.0.1:8080", login["accessToken"], timeout=30,
                        refresh_token=login.get("refreshToken"))
        account = client.call("GET", "/accounts/current")["id"]
        device = client.call("POST", "/devices", {"name": "Worker owner smoke", "type": "FRIDGE"})
        source = client.call("POST", f"/devices/{device['id']}/data-sources", {
            "sourceType": "CUSTOM", "protocol": "REST", "externalId": "worker-" + suffix,
            "mapping": {"telemetryPolicy": {"fields": [{"path": "doorOpen", "key": "doorOpen",
                      "label": "Door open", "type": "boolean", "unit": "1", "store": True, "display": True}]}},
            "adapterConfig": {}, "credentialReferences": {}})
        binding = source["id"]
        until = time.monotonic() + 90
        while True:
            token = capture_token(key, actor, account, binding, 1)
            status, value = read(binding, 1, assertion=token)
            if status == 200:
                assert value["source"]["id"] == binding and value["source"]["accountId"] == account
                assert value["source"]["actorId"] == actor
                break
            if time.monotonic() >= until:
                raise RuntimeError("MACHINE_SOURCE_NOT_READY_" + str(status))
            time.sleep(2)
        assert read(binding, 2, assertion=token)[0] == 401
        assert read(binding, 1, bearer=login["accessToken"])[0] == 401
        proof = str(uuid.uuid4())
        coverage_path = f"/internal/vcl/source-bindings/{binding}/coverage/{proof}"
        pin_body = json.dumps(value["capture"]["pin"], separators=(",", ":")).encode()
        coverage_token = machine_token(key, actor, account, binding, "POST", coverage_path,
                                       "SOURCE_COVERAGE", pin_body)
        coverage_status, coverage = post(binding, coverage_path, pin_body, coverage_token)
        assert coverage_status == 200 and coverage["coverage"] == "UNKNOWN" and coverage["proofId"] == proof
        assert post(binding, coverage_path, pin_body + b" ", coverage_token)[0] == 401
        wrong_path = f"/internal/vcl/source-bindings/{binding}/coverage/{uuid.uuid4()}"
        assert post(binding, wrong_path, pin_body, coverage_token)[0] == 401
        dataset_key = suffix + "-dataset"
        reservation = client.call("POST", "/data/review-reservations", {
            "kind": "COLLECTION", "fieldKeys": ["name"], "idempotencyKey": dataset_key})
        field = reservation["fieldIdsByKey"]["name"]
        collection = {"language": "VCL/1", "kind": "COLLECTION", "spec": {
            "id": reservation["resourceId"], "revision": 1, "label": "Worker dataset smoke",
            "fields": [{"id": field, "label": "Name", "type": "TEXT", "maxLength": 120, "nullable": False}],
            "keyFieldIds": [field], "storage": {"mode": "NATIVE", "indexedFieldIds": [field], "history": "NONE"}}}
        prepared = client.call("POST", "/data/dataset-reviews", {
            "reservationId": reservation["reviewId"], "document": collection})
        apply_review(client, prepared, dataset_key)
        dataset = reservation["resourceId"]
        dataset_path = f"/internal/vcl/datasets/{dataset}?schemaRevision=1"
        dataset_token = machine_token(key, actor, account, dataset, "GET", dataset_path, "DATASET_DESCRIPTION")
        dataset_status, dataset_value = get_dataset(dataset, dataset_token)
        assert dataset_status == 200 and dataset_value["id"] == dataset and dataset_value["schemaRevision"] == 1
        assert get_dataset(dataset, capture_token(key, actor, account, binding, 1))[0] == 401
        client.prepare_planning_session()
        client.call("POST", f"/devices/{device['id']}/decommission")
        status, _ = read(binding, 1, assertion=capture_token(key, actor, account, binding, 1))
        if status not in (403, 404, 409):
            raise RuntimeError("REVOKED_SOURCE_VISIBLE_" + str(status))
        print(json.dumps({"status": "passed", "bindingId": binding, "datasetId": dataset,
                          "coverage": "UNKNOWN", "afterRevoke": status}))
    finally:
        if changed:
            restart()


if __name__ == "__main__":
    try:
        run()
    except Exception as failure:
        print("worker source smoke failed: " + str(failure), file=sys.stderr)
        sys.exit(1)
