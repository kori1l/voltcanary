"""Destructive acceptance uses only two freshly-created synthetic fixtures.
Credentials come from environment and are never persisted to evidence.
"""
import json
import os
import subprocess
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from ai_dashboard_eval.api import ApiError, Client


def main():
    gateway = os.environ.get("VOLT_EVAL_GATEWAY", "http://localhost:8080")
    password = os.environ["VOLT_EVAL_PASSWORD"]
    login = Client(gateway, "", timeout=20).call("POST", "/auth/login", {
        "email": os.environ["VOLT_EVAL_EMAIL"], "password": password})
    client = Client(gateway, login["accessToken"], timeout=20, refresh_token=login.get("refreshToken"))
    account_id = client.call("GET", "/accounts/current/scope")["id"]
    recovery_id = os.environ.get("VOLT_DELETE_RECOVERY_ID")
    if recovery_id:
        recovery_id = str(uuid.UUID(recovery_id))
        deadline = time.monotonic() + 120
        while client.call("GET", f"/devices/{recovery_id}/deletion")["status"] != "COMPLETED":
            if time.monotonic() > deadline: raise AssertionError("Prior deletion did not resume")
            time.sleep(1)
    results = []
    for purge in (False, True):
        device = client.call("POST", "/devices", {"name": "Deletion acceptance " + str(uuid.uuid4())[:8], "type": "FRIDGE"})
        device_id = device["id"]
        print(json.dumps({"createdSyntheticFixture": device_id, "deleteHistory": purge}), flush=True)
        source = client.call("POST", f"/devices/{device_id}/data-sources", {
            "sourceType": "SENSOR", "protocol": "REST", "externalId": "deletion-" + str(uuid.uuid4())})
        token = client.call("POST", f"/devices/{device_id}/data-sources/{source['id']}/token")["token"]
        now = datetime.now(timezone.utc)
        iso = lambda moment: moment.isoformat().replace("+00:00", "Z")
        body = {"deviceId": device_id, "sourceId": source["externalId"], "timestamp": iso(now), "metrics": {"temperatureC": 7.0}}
        deadline = time.monotonic() + 60
        while True:
            try:
                accepted = client.call("POST", "/ingestion/readings", body, token=token)
                if accepted.get("acceptedCount", 0): break
            except ApiError as error:
                if error.status not in (401,403,404,409,503): raise
            if time.monotonic() > deadline: raise AssertionError("Fixture ingestion unavailable")
            time.sleep(1)
        query = f"/telemetry/devices/{device_id}/readings?metric=temperatureC&from={iso(now-timedelta(minutes=1))}&to={iso(now+timedelta(minutes=1))}"
        while not client.call("GET", query)["points"]:
            if time.monotonic() > deadline: raise AssertionError("Fixture telemetry unavailable")
            time.sleep(1)
        receipt = client.call("POST", f"/devices/{device_id}/deletion", {"deleteHistory": purge})
        assert receipt["deleteHistory"] == purge
        repeated = client.call("POST", f"/devices/{device_id}/deletion", {"deleteHistory": purge})
        assert repeated["deviceId"] == device_id
        try:
            client.call("GET", "/devices/" + device_id)
            raise AssertionError("Deleted device remains visible")
        except ApiError as error:
            assert error.status == 404
        deadline = time.monotonic() + 120
        while receipt["status"] != "COMPLETED":
            if time.monotonic() > deadline: raise AssertionError("Cleanup incomplete: " + str(receipt.get("lastError")))
            time.sleep(1)
            receipt = client.call("GET", f"/devices/{device_id}/deletion")
        # Runtime credentials propagate through the existing durable config outbox.
        deadline = time.monotonic() + 45
        revocation_attempt = 0
        while True:
            try:
                rejected = client.call("POST", "/ingestion/readings", {**body,"timestamp":iso(now+timedelta(seconds=1+revocation_attempt))}, token=token)
                if rejected.get("acceptedCount", 0) == 0 and rejected.get("rejectedCount", 0) > 0: break
            except ApiError as error:
                assert error.status in (401,403,404,409)
                break
            if time.monotonic() > deadline: raise AssertionError("Deleted source credential remains accepted after runtime propagation")
            revocation_attempt += 1
            time.sleep(1)
        # Deliberately replay a raw fixture event after deletion to exercise the
        # production PostgreSQL fence independently of revoked ingress credentials.
        late = {"eventId":str(uuid.uuid4()),"accountId":account_id,"deviceId":device_id,
                "sourceId":source["externalId"],"timestamp":iso(now+timedelta(seconds=30)),
                "metrics":{"temperatureC":99.0},"receivedAt":iso(datetime.now(timezone.utc))}
        published = subprocess.run(["docker","compose","exec","-T","redpanda","rpk","topic","produce","telemetry.raw.v1","-k",device_id],
            input=json.dumps(late)+"\n",text=True,capture_output=True)
        assert published.returncode == 0, "Synthetic late-event publication failed"
        time.sleep(3)
        if not purge:
            points = client.call("GET", query)["points"]
            assert len(points) == 1 and points[0]["value"] == 7.0
        else:
            try:
                client.call("GET", query)
                raise AssertionError("Purged history still publicly readable")
            except ApiError as error:
                assert error.status in (403,404)
        results.append({"deviceId":device_id,"deleteHistory":purge,"status":receipt["status"],"completedSteps":receipt["completedSteps"],"hiddenFromRegistry":True,"credentialRevoked":True,"historyPolicyVerified":True,"lateRawEventPublished":True})
        token = None
    # Exercise real private connector cleanup with one explicitly synthetic binding.
    config_path = Path("docker-data/hsl/config.json")
    before = json.loads(config_path.read_text(encoding="utf-8-sig"))
    existing = {item["deviceId"]: item for item in before.get("vehicles", [])}
    device = client.call("POST", "/devices", {"name": "Synthetic connector deletion " + str(uuid.uuid4())[:8], "type": "VEHICLE"})
    device_id = device["id"]
    print(json.dumps({"createdSyntheticConnectorFixture": device_id}), flush=True)
    private_code = """import json,sys; from urllib.request import Request,urlopen
request=json.load(sys.stdin)
raw=json.dumps(request['body']).encode()
with urlopen(Request('http://localhost:8091'+request['path'],data=raw,headers={'Authorization':request['authorization'],'Content-Type':'application/json'},method='POST'),timeout=30) as response:
 print(response.read().decode())
"""
    def connector(path, body):
        process = subprocess.run(["docker", "compose", "-f", "docker-compose.yml", "-f", "deploy/docker-compose/hsl.yml", "exec", "-T", "hsl-connector", "python", "-c", private_code],
            input=json.dumps({"authorization":"Bearer "+client.token,"path":path,"body":body}),text=True,capture_output=True)
        if process.returncode: raise AssertionError("Private synthetic connector setup failed")
        return json.loads(process.stdout)
    binding = {"mode":"bus","vehicleKey":"9999-99999","creationKey":"deletion-smoke-"+str(uuid.uuid4())}
    connector("/availability", binding)
    connector("/bindings", {**binding,"deviceId":device_id})
    receipt = client.call("POST", f"/devices/{device_id}/deletion", {"deleteHistory":True})
    deadline = time.monotonic()+120
    while receipt["status"] != "COMPLETED":
        if time.monotonic()>deadline: raise AssertionError("Connector cleanup incomplete: "+str(receipt.get("lastError")))
        time.sleep(1)
        receipt=client.call("GET", f"/devices/{device_id}/deletion")
    after=json.loads(config_path.read_text(encoding="utf-8-sig"))
    remaining={item["deviceId"]:item for item in after.get("vehicles",[])}
    assert device_id not in remaining
    assert all(remaining.get(key)==value for key,value in existing.items())
    results.append({"deviceId":device_id,"deleteHistory":True,"status":receipt["status"],"connectorBindingRemoved":True,"existingBindingsPreserved":len(existing)})
    output = {"status":"PASSED","synthetic":True,"results":results,"recoveredReceiptId":recovery_id}
    Path("artifacts/device-deletion-docker-smoke.json").write_text(json.dumps(output,indent=2),encoding="utf-8")
    print(json.dumps(output))


if __name__ == "__main__":
    main()
