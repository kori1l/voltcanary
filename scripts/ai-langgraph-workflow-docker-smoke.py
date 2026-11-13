"""Ordinary-API LangGraph lifecycle against a disposable deterministic provider.

No real model calls or demo-account changes. Credentials and provider requests
stay in memory; evidence contains only synthetic IDs/definitions and checks.
"""
import argparse
from collections import deque
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import time
import uuid

from ai_dashboard_eval.api import ApiError, Client, ROOT, sha, write_json
from ai_dashboard_eval.execution import archive_dashboard
from ai_dashboard_eval.fixtures import VehicleFixture
from ai_dashboard_eval.grading import definition


class Provider(ThreadingHTTPServer):
    def __init__(self, port):
        super().__init__(("0.0.0.0", port), Handler)
        self.expected = deque()
        self.calls = 0
        self.errors = []
        self.lock = threading.Lock()
        self.created_name = ""
        self.edited_name = ""

    def expect(self, *phases):
        with self.lock:
            if self.expected:
                raise AssertionError("Previous provider phases not consumed")
            self.expected.extend(phases)

    def proposal(self, request):
        with self.lock:
            self.calls += 1
            if self.calls > 12 or not self.expected:
                raise AssertionError("Unexpected provider call; no automatic fixture retry")
            phase = self.expected.popleft()
        current = json.loads(request["messages"][-1]["content"])
        if "formatCorrection" in current:
            raise AssertionError("Fixture contract failed; correction must not rescue the smoke")
        root = {"version": "assistant-v2"}
        if phase.startswith("plan"):
            assert current["kind"] == "planner.request"
            intent = "CREATE_DASHBOARD" if phase == "plan_create" else "UPDATE_DASHBOARD_DRAFT"
            selected = [x["ref"] for x in current["resourceChoices"] if x["kind"] == "device"]
            assert selected, "Actual authorized device choices required"
            return {**root, "kind": "planner.response", "decision": "PLAN", "explanation": "Isolated workflow fixture",
                    "targetStepId": None, "supersedesStepIds": [], "question": None,
                    "steps": [{"stepId": "dashboard", "intentId": intent, "dependsOn": [],
                               "inputs": [{"field": "deviceIds", "binding": {"kind": "resource_refs", "refs": selected}}],
                               "goalIds": ["goal"]}],
                    "proposedGoals": [{"goalId": "goal", "text": "Create or edit the selected synthetic dashboard"}],
                    "supersedesGoalIds": []}
        assert current["kind"] == "step.request"
        root.update(kind="step.response", explanation="Deterministic integration fixture")
        if phase == "question":
            return {**root, "outcome": "QUESTION", "question": {"text": "Как назвать дашборд?",
                    "missingFields": ["name"], "choices": []}}
        if phase == "fail":
            return {**root, "outcome": "UNSUPPORTED", "diagnostic": {"category": "INVALID_PLAN",
                    "code": "FIXTURE_PARTIAL_FAILURE", "message": "Controlled planning failure; saved draft must remain unchanged",
                    "fieldPaths": [], "retriable": False}}
        intent = current["intentId"]
        operations = [{"id": "name", "op": "rename_dashboard",
                       "name": self.created_name if phase == "create" else self.edited_name}]
        payload = {"protocol": "dashboard-operations-v1", "operations": operations}
        if phase == "create":
            assert intent == "CREATE_DASHBOARD"
            workflow = next(x["data"]["context"] for x in current["loadResults"] if x["loaderId"] == "workflow.context")
            speed = next(x for x in workflow["dashboardDataChoices"] if x.get("key") == "speedKmh")
            operations.extend([{"id": "speed", "op": "show_current", "dataRef": speed["dataRef"]},
                               {"id": "note", "op": "add_note", "title": "Synthetic fixture", "text": "Recorded test observations"}])
            payload["dashboardPeriod"] = "LAST_1_HOUR"
        else:
            assert phase == "edit" and intent == "UPDATE_DASHBOARD_DRAFT"
        return {**root, "outcome": "PREPARED", "command": {"schemaId": intent + ":command:2",
                "schemaVersion": "2", "payload": payload}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                parts = []
                while True:
                    size = int(self.rfile.readline().split(b";", 1)[0], 16)
                    if not size:
                        self.rfile.readline()
                        break
                    parts.append(self.rfile.read(size))
                    self.rfile.read(2)
                body = b"".join(parts)
            else:
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            if len(body) > 1_000_000:
                raise AssertionError("Fixture request too large")
            candidate = self.server.proposal(json.loads(body))
            response = {"id": "langgraph-fixture", "object": "chat.completion", "created": 1,
                        "model": "langgraph-workflow-fixture", "choices": [{"index": 0,
                            "message": {"role": "assistant", "content": json.dumps(candidate, ensure_ascii=False)}, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}}
            data = json.dumps(response).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as error:
            self.server.errors.append(type(error).__name__)
            self.send_error(500, "DETERMINISTIC_FIXTURE_CONTRACT_FAILURE")


def require(value, code):
    if not value:
        raise AssertionError(code)


def wait_health(seconds=300, port=8088):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if Client(f"http://127.0.0.1:{port}", "", timeout=4).call("GET", "/actuator/health")["status"] == "UP":
                return
        except ApiError:
            pass
        time.sleep(2)
    raise AssertionError("AI_SERVICE_HEALTH_TIMEOUT")


def restart():
    subprocess.run(["docker", "compose", "restart", "ai-agent-service"], check=True, capture_output=True, timeout=60)
    wait_health()


def run(args, server, report):
    anonymous = Client(args.gateway, "", timeout=15)
    email, password = "langgraph-" + uuid.uuid4().hex + "@example.test", "Workflow!" + uuid.uuid4().hex
    anonymous.call("POST", "/auth/register", {"email": email, "password": password})
    login = anonymous.call("POST", "/auth/login", {"email": email, "password": password})
    client = Client(args.gateway, login["accessToken"], timeout=180, refresh_token=login.get("refreshToken"))
    client.call("GET", "/accounts/current")
    profile = client.call("GET", "/ai-agent/orchestration/profile")
    require(profile.get("graphEngine") == "langgraph4j-1.9.9" and profile.get("enabled"), "GRAPH_PROFILE_NOT_ACTIVE")
    require(not profile.get("modelJudge"), "FIXTURE_PROFILE_MUST_NOT_USE_MODEL_JUDGE")
    report["profile"] = profile
    provider = client.call("POST", "/ai-agent/model-providers", {"name": "Isolated LangGraph workflow fixture",
                "providerType": "OPENAI_COMPATIBLE", "baseUrl": args.fixture_base_url,
                "apiKey": "local-fixture-only", "modelId": "langgraph-workflow-fixture", "makeDefault": True,
                "supportsJson": False, "supportsTools": False, "maxOutputTokens": 4000, "timeoutMs": 10000})
    model = provider["models"][0]
    marker = "EVAL_" + uuid.uuid4().hex[:12] + "_langgraph"
    server.created_name, server.edited_name = "QA " + marker, "Edited " + marker
    fixture = VehicleFixture(client, marker)
    dashboard = None
    checks = report["checks"]

    def check(code, passed):
        checks.append({"id": code, "passed": bool(passed)})
        write_json(args.output, report)
        print(json.dumps({"check": code, "passed": bool(passed)}), flush=True)
        require(passed, code)

    def conversation():
        identifier = str(uuid.uuid4())
        client.call("POST", "/ai-agent/conversations", {"conversationId": identifier})
        report.setdefault("conversationIds", []).append(identifier)
        return identifier

    def turn(chat, text, phases, base=None):
        detail = client.call("GET", "/ai-agent/conversations/" + chat)
        body = {"requestId": str(uuid.uuid4()), "expectedRevision": detail["conversation"]["revision"],
                "text": text, "context": {"deviceIds": fixture.devices}, "providerId": provider["id"], "modelConfigId": model["id"]}
        if base:
            body["parentPlanId"] = base
        server.expect(*phases)
        result = client.call("POST", f"/ai-agent/conversations/{chat}/messages", body)
        trace = client.call("GET", f"/ai-agent/conversations/{chat}/turns/{body['requestId']}/trace/export")
        report.setdefault("turns", []).append({"conversationId": chat, "requestId": body["requestId"],
                    "status": result.get("pendingStatus"), "modelCalls": sum(e.get("stage") == "MODEL_CALL" and e.get("status") == "STARTED" for e in trace),
                    "failures": [{"stage": e.get("stage"), "code": e.get("code")} for e in trace if e.get("status") == "FAILED"]})
        write_json(args.output, report)
        require(not server.expected and not server.errors, "UNCONSUMED_OR_REJECTED_PROVIDER_PHASE")
        return result, body

    def execute(plan):
        return client.call("POST", f"/ai-agent/plans/{plan['planId']}/execute",
                           {"confirmed": True, "expectedVersion": plan["version"], "payloadDigest": plan["payloadDigest"]})

    try:
        fixture.create()
        report["fixture"] = fixture.metadata()
        write_json(args.output, report)
        chat = conversation()
        asked, _ = turn(chat, "Для выбранного устройства создай дашборд. Сначала уточни название.", ["plan_create", "question"])
        check("persisted_clarification", asked["plan"]["planningStatus"] == "NEEDS_INPUT")
        if args.restart_service:
            restart()
        restored = client.call("GET", "/ai-agent/conversations/" + chat)
        check("question_survives_restart", restored["orchestration"]["pendingQuestion"] == asked["orchestration"]["pendingQuestion"])
        prepared, request = turn(chat, server.created_name, ["create"])
        plan = prepared["plan"]
        require(plan["planningStatus"] == "READY", "CREATE_REVIEW_NOT_READY")
        reviewed = definition(plan)
        calls = server.calls
        replay = client.call("POST", f"/ai-agent/conversations/{chat}/messages", request)
        check("turn_replay_without_model", server.calls == calls and definition(replay["plan"]) == reviewed)
        try:
            client.call("POST", f"/ai-agent/plans/{plan['planId']}/execute", {"confirmed": True, "expectedVersion": 999,
                        "payloadDigest": plan["payloadDigest"]})
            raise AssertionError("STALE_CONFIRMATION_EXECUTED")
        except ApiError as error:
            check("stale_confirmation_denied", error.status == 409)
        receipt = execute(plan)
        check("confirmed_create", receipt["status"] == "SUCCESS")
        dashboard = receipt["actions"][0]["result"]["dashboardId"]
        report["dashboardId"] = dashboard
        check("exact_create_readback", client.call("GET", f"/dashboards/{dashboard}/draft")["definition"] == reviewed)
        if args.restart_service:
            restart()
        check("receipt_reuse_after_restart", execute(plan)["actions"][0]["result"]["dashboardId"] == dashboard and server.calls == calls)
        failed, _ = turn(conversation(), "Переименуй созданный дашборд.", ["plan_edit", "fail"], plan["planId"])
        check("partial_failure_retains_created_draft", failed["pendingStatus"] == "FAILED"
              and client.call("GET", f"/dashboards/{dashboard}/draft")["definition"] == reviewed)
        edited, _ = turn(conversation(), "Переименуй только дашборд в «" + server.edited_name + "», остальное сохрани.",
                         ["plan_edit", "edit"], plan["planId"])
        update = edited["plan"]
        require(update["planningStatus"] == "READY", "UPDATE_REVIEW_NOT_READY")
        expected = deepcopy(reviewed)
        expected["name"] = server.edited_name
        check("independent_preservation_oracle", definition(update) == expected)
        updated = execute(update)
        check("same_id_edit", updated["status"] == "SUCCESS" and updated["actions"][0]["result"]["dashboardId"] == dashboard)
        check("exact_update_readback", client.call("GET", f"/dashboards/{dashboard}/draft")["definition"] == expected)
        check("update_receipt_replay", execute(update)["actions"][0]["result"]["dashboardId"] == dashboard)
        page = client.call("GET", "/dashboards?limit=100&status=ALL")
        check("one_dashboard_only", not page.get("hasNext") and [x["id"] for x in page["items"]] == [dashboard])
        report["reviewedDefinition"], report["updatedDefinition"] = reviewed, expected
        report["status"] = "PASSED"
    finally:
        failures = []
        if dashboard:
            try:
                archive_dashboard(client, dashboard)
            except ApiError as error:
                failures.append({"resource": "dashboard", "status": error.status})
        failures.extend(fixture.cleanup())
        try:
            client.call("DELETE", "/ai-agent/model-providers/" + provider["id"])
        except ApiError as error:
            failures.append({"resource": "fixture_provider", "status": error.status})
        report["cleanup"] = {"completed": not failures, "failures": failures}
        if failures:
            report["status"] = "FAILED"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--fixture-port", type=int, default=8128)
    parser.add_argument("--fixture-base-url", default="http://host.docker.internal:8128/v1")
    parser.add_argument("--restart-service", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/langgraph4j-docker-workflow/summary.json")
    args = parser.parse_args()
    require(ROOT / "artifacts" in args.output.resolve().parents, "OUTPUT_MUST_BE_IGNORED_ARTIFACT")
    report = {"status": "RUNNING", "scope": "DETERMINISTIC_INTEGRATION_NOT_MODEL_QUALITY", "checks": [], "realModelCalls": 0,
              "harnessHash": sha(Path(__file__))}
    source = ROOT / "backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent"
    report["sourceHashes"] = {p: sha(source / p) for p in ["adapter/langgraph4j/LangGraphAgentRunner.java",
          "orchestration/plan/IntentOrchestration.java", "orchestration/plan/IntentPlanActions.java", "adapter/JdbcIntentPlanStore.java"]}
    server = Provider(args.fixture_port)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        for port in [8080, 8081, 8082, 8083, 8084, 8088, 8089]:
            wait_health(port=port)
        run(args, server, report)
    except (ApiError, AssertionError) as error:
        report.update(status="FAILED", errorCode=error.code if isinstance(error, ApiError) else str(error),
                      httpStatus=error.status if isinstance(error, ApiError) else 0)
    finally:
        server.shutdown()
        server.server_close()
        if report["status"] != "PASSED":
            report["status"] = "FAILED"
        report["fixtureProviderCalls"], report["fixtureErrors"] = server.calls, server.errors
        write_json(args.output, report)
    print(json.dumps({key: report.get(key) for key in ["status", "errorCode", "httpStatus", "fixtureProviderCalls", "cleanup"]}))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
