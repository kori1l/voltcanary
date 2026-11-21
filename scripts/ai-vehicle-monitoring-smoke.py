"""Ordinary signed AI -> Vehicles review -> confirmation -> manual edit -> replay.

Default: isolated account and deterministic local provider. --live uses the existing
account/model from VOLTCANARY_DEMO_EMAIL/PASSWORD without changing its provider.
Credentials, provider bodies and tokens are never written to the summary.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import threading
import uuid

from ai_dashboard_eval.api import Client, write_json

spec = importlib.util.spec_from_file_location("graph_fixture", Path(__file__).with_name("ai-langgraph-workflow-docker-smoke.py"))
graph = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graph)
INTENT = "UPDATE_VEHICLE_MONITORING"


class VehicleProvider(graph.Provider):
    def proposal(self, request):
        self.calls += 1
        if self.calls > 2:
            raise AssertionError("Unexpected model retry")
        current = json.loads(request["messages"][-1]["content"])
        if "formatCorrection" in current:
            raise AssertionError("Fixture contract must work without correction")
        if current["kind"] == "planner.request":
            selected, = [x["ref"] for x in current["resourceChoices"] if x["kind"] == "device"]
            return {"version": "assistant-v2", "kind": "planner.response", "decision": "PLAN", "explanation": "Configure existing Vehicles monitoring",
                    "targetStepId": None, "supersedesStepIds": [], "question": None, "supersedesGoalIds": [],
                    "proposedGoals": [{"goalId": "monitor", "text": "Add the depot zone and set speed limit"}],
                    "steps": [{"stepId": "vehicle", "intentId": INTENT, "dependsOn": [], "goalIds": ["monitor"],
                               "inputs": [{"field": "deviceId", "binding": {"kind": "resource_refs", "refs": [selected]}}]}]}
        assert current["intentId"] == INTENT
        context = next(x["data"]["context"] for x in current["loadResults"] if x["loaderId"] == "workflow.context")
        assert context["selectedPoint"] == {"latitude": 60, "longitude": 24}
        return {"version": "assistant-v2", "kind": "step.response", "explanation": "Review existing vehicle monitoring", "outcome": "PREPARED", "command": {"schemaId": INTENT + ":command:1", "schemaVersion": "1",
                "payload": {"deviceId": context["vehicle"]["id"], "expectedRevision": context["monitoring"]["revision"],
                            "sourceId": context["selectedSourceId"], "speedLimitKmh": 60, "idleMinutes": None,
                            "zoneEdits": [{"op": "ADD", "zoneId": None, "name": "Depot", "latitude": 60, "longitude": 24, "radiusMetres": 200}]}}}


def run(args, report):
    graph.wait_health(seconds=180)
    anonymous = Client(args.gateway, "", timeout=30)
    email = os.environ["VOLTCANARY_DEMO_EMAIL"] if args.live else "vehicle-" + uuid.uuid4().hex + "@example.test"
    password = os.environ["VOLTCANARY_DEMO_PASSWORD"] if args.live else "Vehicle!" + uuid.uuid4().hex
    if not args.live:
        anonymous.call("POST", "/auth/register", {"email": email, "password": password})
    login = anonymous.call("POST", "/auth/login", {"email": email, "password": password})
    client = Client(args.gateway, login["accessToken"], timeout=240, refresh_token=login.get("refreshToken"))
    client.call("GET", "/accounts/current")
    server = None
    if not args.live:
        server = VehicleProvider(args.fixture_port, 2)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        provider = client.call("POST", "/ai-agent/model-providers", {"name": "Isolated Vehicles fixture", "providerType": "OPENAI_COMPATIBLE",
                               "baseUrl": f"http://host.docker.internal:{args.fixture_port}/v1", "apiKey": "local-fixture-only", "modelId": "vehicle-fixture",
                               "makeDefault": True, "supportsJson": False, "supportsTools": False, "maxOutputTokens": 4000, "timeoutMs": 10000})
    device = client.call("POST", "/devices", {"name": "Vehicles verification " + uuid.uuid4().hex[:8], "type": "VEHICLE"}, {"Idempotency-Key": str(uuid.uuid4())})["id"]
    report["deviceId"] = device
    try:
        source = client.call("POST", f"/devices/{device}/data-sources", {"sourceType": "API", "protocol": "REST", "externalId": "vehicle-fixture-" + uuid.uuid4().hex})["id"]
        path = f"/telemetry/devices/{device}/vehicle-monitoring"
        original = {"speedLimitKmh": 80, "idleMinutes": 3, "zones": [{"id": str(uuid.uuid4()), "name": "Keep this zone", "latitude": 61, "longitude": 25, "radiusMetres": 100}]}
        first = client.call("POST", path + "/reviews", {"expectedRevision": 0, "idempotencyKey": str(uuid.uuid4()), "settings": original})
        client.call("POST", path + "/reviews/" + first["id"] + "/confirm", {"digest": first["digest"], "idempotencyKey": first["idempotencyKey"]})
        chat = str(uuid.uuid4())
        client.call("POST", "/ai-agent/conversations", {"conversationId": chat})
        request = {"requestId": str(uuid.uuid4()), "expectedRevision": 0,
                   "text": "Для выбранного автомобиля добавь геозону Depot вокруг выбранной на карте точки, радиус 200 метров. Поставь лимит скорости 60 км/ч. Остальные зоны и пороги сохрани. Используй существующую вкладку Vehicles, новый дашборд не создавай.",
                   "context": {"deviceId": device, "sourceId": source, "vehiclePoint": {"latitude": 60, "longitude": 24}}}
        if not args.live:
            request.update(providerId=provider["id"], modelConfigId=provider["models"][0]["id"])
        detail = client.call("POST", f"/ai-agent/conversations/{chat}/messages", request)
        report["conversationId"] = chat
        report["requestId"] = request["requestId"]
        report["pendingStatus"] = detail.get("pendingStatus")
        trace = client.call("GET", f"/ai-agent/conversations/{chat}/turns/{request['requestId']}/trace/export")
        report["modelCalls"] = sum(item.get("stage") == "MODEL_CALL" and item.get("status") == "STARTED" for item in trace)
        report["failures"] = [{"stage": item.get("stage"), "code": item.get("code"), "validation": (item.get("details") or {}).get("validation")} for item in trace if item.get("status") == "FAILED"]
        plan = detail.get("plan") or {}
        assert plan.get("planningStatus") == "READY", "VEHICLE_REVIEW_NOT_READY"
        action, = plan["actions"]
        assert action["tool"] == INTENT, "WRONG_VEHICLE_WORKFLOW"
        review = action["parameters"]["review"]
        assert review["before"] == original and review["baseRevision"] == 1
        assert review["after"]["idleMinutes"] == 3 and original["zones"][0] in review["after"]["zones"]
        assert review["after"]["speedLimitKmh"] == 60 and len(review["after"]["zones"]) == 2
        added, = [zone for zone in review["after"]["zones"] if zone["id"] != original["zones"][0]["id"]]
        assert {key: added[key] for key in ("name", "latitude", "longitude", "radiusMetres")} == {"name": "Depot", "latitude": 60, "longitude": 24, "radiusMetres": 200}
        uuid.UUID(added["id"])
        assert client.call("GET", path + "/snapshot")["settings"] == original, "PREPARATION_WROTE_SETTINGS"
        def execute():
            return client.call("POST", f"/ai-agent/plans/{plan['planId']}/execute", {"confirmed": True, "expectedVersion": plan["version"], "payloadDigest": plan["payloadDigest"]})
        result = execute()
        assert result["status"] == "SUCCESS", "VEHICLE_CONFIRMATION_FAILED"
        assert client.call("GET", path + "/snapshot")["settings"] == review["after"]
        assert result["actions"][0]["result"]["route"].startswith("/vehicles?deviceId=" + device)
        later = dict(review["after"], speedLimitKmh=50)
        manual = client.call("POST", path + "/reviews", {"expectedRevision": 2, "idempotencyKey": str(uuid.uuid4()), "settings": later})
        client.call("POST", path + "/reviews/" + manual["id"] + "/confirm", {"digest": manual["digest"], "idempotencyKey": manual["idempotencyKey"]})
        assert execute() == result, "AI_RECEIPT_REPLAY_CHANGED"
        assert client.call("GET", path + "/snapshot")["settings"] == later, "REPLAY_OVERWROTE_MANUAL_EDIT"
        assert client.call("GET", path + "/reviews/" + review["id"])["after"] == review["after"]
        report.update(passed=True, checks=["same_vehicle_review", "omitted_settings_preserved", "no_write_before_confirmation", "exact_owner_readback", "vehicles_result_route", "manual_edit_survives_replay", "immutable_original_review"])
    finally:
        client.call("DELETE", "/devices/" + device)
        if server:
            server.shutdown()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8080")
    parser.add_argument("--fixture-port", type=int, default=8128)
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("artifacts/ai-vehicle-monitoring-summary.json"))
    args = parser.parse_args()
    report = {"mode": "live" if args.live else "deterministic", "passed": False}
    try:
        run(args, report)
    finally:
        write_json(args.output, report)
    print(json.dumps({"passed": report["passed"], "mode": report["mode"]}))
