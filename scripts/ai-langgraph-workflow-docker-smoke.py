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
from urllib.parse import parse_qs, urlsplit

from ai_dashboard_eval.api import ApiError, Client, ROOT, sha, write_json
from ai_dashboard_eval.execution import archive_dashboard
from ai_dashboard_eval.fixtures import VehicleFixture
from ai_dashboard_eval.grading import definition


class Provider(ThreadingHTTPServer):
    def __init__(self, port, call_limit):
        super().__init__(("0.0.0.0", port), Handler)
        self.expected = deque()
        self.calls = 0
        self.call_limit = call_limit
        self.errors = []
        self.lock = threading.Lock()
        self.created_name = ""
        self.edited_name = ""
        self.device_proposal = {}
        self.chain_source_id = None
        self.switch_device_label = None
        self.simulator_command = None
        self.mapping_command = None
        self.table_command = None
        self.registry_digest = None
        self.hsl_key = "9999-" + str(uuid.uuid4().int % 100000).zfill(5)

    def expect(self, *phases):
        with self.lock:
            if self.expected:
                raise AssertionError("Previous provider phases not consumed")
            self.expected.extend(phases)

    def proposal(self, request):
        with self.lock:
            self.calls += 1
            if self.calls > self.call_limit or not self.expected:
                raise AssertionError("Unexpected provider call; no automatic fixture retry")
            phase = self.expected.popleft()
        current = json.loads(request["messages"][-1]["content"])
        if "formatCorrection" in current:
            raise AssertionError("Fixture contract failed; correction must not rescue the smoke")
        root = {"version": "assistant-v2"}
        if phase in ("consult_delegate", "consult_load", "consult_child", "consult_join", "consult_loaded"):
            assert current["kind"] == "consultation.assignment"
            reply = {key: current[key] for key in ("taskId", "attemptId", "requirementsRevision", "readSetDigest")}
            if phase == "consult_delegate":
                child, = current["capabilities"]["delegates"]
                return {**reply, "outcome": "DELEGATE", "requests": [{"parentTaskId": current["taskId"],
                        "requestKey": "readability", "skillId": child["skillId"], "skillVersion": child["skillVersion"],
                        "goalIds": current["goalIds"], "requirementsRevision": current["requirementsRevision"],
                        "inputRefs": [], "question": "Assess readability", "resultSchemaId": child["resultSchemaId"]}]}
            if phase == "consult_load":
                loader, = current["capabilities"]["loaders"]
                return {**reply, "outcome": "NEED_DATA", "requests": [{"loaderId": loader["loaderId"],
                        "argumentsSchemaId": loader["argumentsSchemaId"], "arguments": {}}]}
            if phase == "consult_child":
                assert current["skillId"] == "name-readability"
            if phase == "consult_join":
                assert current["childResults"][0]["payload"]["assessment"] == "Readable"
            if phase == "consult_loaded":
                assert current["loaderResults"][0]["deviceLabels"]
            return {**reply, "outcome": "ANSWER", "payload": {"assessment": "Readable"},
                    "evidenceRefs": [], "unresolvedSlotIds": []}
        if phase == "consult_question":
            assert current["kind"] == "consultation.assignment"
            slot, = current["slots"]
            assert slot["status"] == "UNKNOWN"
            return {**{key: current[key] for key in ("taskId", "attemptId", "requirementsRevision", "readSetDigest")},
                    "outcome": "NEED_USER", "questions": [{"text": "Как назвать дашборд?", "reason": "Name is missing",
                    "slotIds": [slot["id"]], "choices": [], "evidenceRefs": []}]}
        if phase == "consult_name":
            assert current["kind"] == "consultation.assignment"
            assert [slot["value"]["value"] for slot in current["slots"]] == [self.created_name]
            return {**{key: current[key] for key in ("taskId", "attemptId", "requirementsRevision", "readSetDigest")},
                    "outcome": "ANSWER", "payload": {"assessment": "Confirmed display label"},
                    "evidenceRefs": [], "unresolvedSlotIds": []}
        if phase.startswith("plan"):
            assert current["kind"] == "planner.request"
            if phase in ("plan_mapping", "plan_table"):
                intent, goal = {
                    "plan_mapping": ("APPLY_SOURCE_MAPPING", "Map the selected Boolean field"),
                    "plan_table": ("CREATE_NATIVE_TABLE", "Create the requested native table"),
                }[phase]
                return {**root, "kind": "planner.response", "decision": "PLAN", "explanation": goal,
                        "targetStepId": None, "supersedesStepIds": [], "question": None, "supersedesGoalIds": [],
                        "proposedGoals": [{"goalId": "goal", "text": goal}],
                        "steps": [{"stepId": "resource", "intentId": intent, "dependsOn": [],
                                   "inputs": [], "goalIds": ["goal"]}]}
            if phase == "plan_simulator":
                intent, payload = self.simulator_command
                inputs = []
                if "deviceId" in payload:
                    selected, = [choice["ref"] for choice in current["resourceChoices"] if choice["kind"] == "device"]
                    inputs.append({"field": "deviceId", "binding": {"kind": "resource_refs", "refs": [selected]}})
                return {**root, "kind": "planner.response", "decision": "PLAN", "explanation": "Prepare simulator action",
                        "targetStepId": None, "supersedesStepIds": [], "question": None, "supersedesGoalIds": [],
                        "proposedGoals": [{"goalId": "goal", "text": "Perform the requested simulator action"}],
                        "steps": [{"stepId": "simulator", "intentId": intent, "dependsOn": [],
                                   "inputs": inputs,
                                   "goalIds": ["goal"]}]}
            if phase == "plan_chain":
                def output(field, step, key):
                    return {"field": field, "binding": {"kind": "step_output", "stepId": step, "outputKey": key}}
                return {**root, "kind": "planner.response", "decision": "PLAN", "explanation": "Prepare dependent fixture",
                        "targetStepId": None, "supersedesStepIds": [], "question": None, "supersedesGoalIds": [],
                        "proposedGoals": [{"goalId": "goal", "text": "Create the device, source and dashboard"}],
                        "steps": [
                            {"stepId": "device", "intentId": "CREATE_DEVICE", "dependsOn": [], "inputs": [], "goalIds": ["goal"]},
                            {"stepId": "source", "intentId": "CREATE_DATA_SOURCE", "dependsOn": ["device"],
                             "inputs": [output("deviceId", "device", "deviceId")], "goalIds": ["goal"]},
                            {"stepId": "dashboard", "intentId": "CREATE_DASHBOARD", "dependsOn": ["device", "source"],
                             "inputs": [output("deviceId", "device", "deviceId"), output("sourceId", "source", "dataSourceId")],
                             "goalIds": ["goal"]}]}
            if phase in ("plan_device", "plan_source"):
                source = phase == "plan_source"
                inputs = [{"field": "deviceId", "binding": {"kind": "resource_refs",
                           "refs": [x["ref"] for x in current["resourceChoices"] if x["kind"] == "device"]}}] if source else []
                return {**root, "kind": "planner.response", "decision": "PLAN", "explanation": "Create fixture device",
                        "targetStepId": None, "supersedesStepIds": [], "question": None,
                        "steps": [{"stepId": "source" if source else "device",
                                   "intentId": "CREATE_DATA_SOURCE" if source else "CREATE_DEVICE", "dependsOn": [],
                                   "inputs": inputs, "goalIds": ["goal"]}],
                        "proposedGoals": [{"goalId": "goal", "text": "Create the requested device"}], "supersedesGoalIds": []}
            intent = {"plan_create": "CREATE_DASHBOARD", "plan_pair": "CREATE_DASHBOARD",
                      "plan_switch": "CREATE_DASHBOARD", "plan_refine": "REFINE_DASHBOARD_REVIEW",
                      "plan_edit": "UPDATE_DASHBOARD_DRAFT"}[phase]
            selected = [x["ref"] for x in current["resourceChoices"] if x["kind"] == "device"]
            assert selected, "Actual authorized device choices required"
            if phase in ("plan_switch", "plan_pair"):
                assert len(selected) == 2
            if phase == "plan_switch":
                selected = [x["ref"] for x in current["resourceChoices"]
                            if x["kind"] == "device" and x["label"] == self.switch_device_label]
                assert len(selected) == 1
            return {**root, "kind": "planner.response", "decision": "PLAN", "explanation": "Isolated workflow fixture",
                    "targetStepId": None, "supersedesStepIds": [], "question": None,
                    "steps": [{"stepId": "dashboard", "intentId": intent, "dependsOn": [],
                               "inputs": [{"field": "deviceIds", "binding": {"kind": "resource_refs", "refs": selected}}],
                               "goalIds": ["goal"]}],
                    "proposedGoals": [{"goalId": "goal", "text": "Create or edit the selected synthetic dashboard"}],
                    "supersedesGoalIds": []}
        assert current["kind"] == "step.request"
        root.update(kind="step.response", explanation="Deterministic integration fixture")
        if phase == "table":
            assert current["intentId"] == "CREATE_NATIVE_TABLE"
            workflow = next(item["data"]["context"] for item in current["loadResults"]
                            if item["loaderId"] == "workflow.context")
            assert workflow["registryDigest"] == self.registry_digest and not workflow["ruleExecutionReady"]
            return {**root, "outcome": "PREPARED", "command": {"schemaId": "CREATE_NATIVE_TABLE:command:1",
                    "schemaVersion": "1", "payload": self.table_command}}
        if phase == "mapping":
            assert current["intentId"] == "APPLY_SOURCE_MAPPING"
            workflow = next(item["data"]["context"] for item in current["loadResults"]
                            if item["loaderId"] == "workflow.context")
            assert workflow["schemaRevision"] == 1
            assert self.mapping_command["fieldId"] in {field["id"] for field in workflow["fields"]}
            return {**root, "outcome": "PREPARED", "command": {"schemaId": "APPLY_SOURCE_MAPPING:command:1",
                    "schemaVersion": "1", "payload": self.mapping_command}}
        if phase == "simulator":
            intent, payload = self.simulator_command
            assert current["intentId"] == intent
            return {**root, "outcome": "PREPARED", "command": {"schemaId": intent + ":command:1",
                    "schemaVersion": "1", "payload": payload}}
        if phase == "source":
            assert current["intentId"] == "CREATE_DATA_SOURCE"
            return {**root, "outcome": "PREPARED", "command": {"schemaId": "CREATE_DATA_SOURCE:command:1",
                    "schemaVersion": "1", "payload": {"provider": "HSL_HFP", "mode": "bus",
                    "vehicleKey": self.hsl_key, "deviceId": current["contract"]["resolvedInputs"]["deviceId"]}}}
        if phase == "device":
            assert current["intentId"] == "CREATE_DEVICE"
            workflow = next(x["data"]["context"] for x in current["loadResults"] if x["loaderId"] == "workflow.context")
            payload = dict(self.device_proposal)
            assert payload["type"] in {entry["code"] for entry in workflow["deviceTypes"]}
            if "locationId" in payload:
                assert workflow["locationId"] == payload["locationId"]
                payload["locationId"] = workflow["location"]["ref"]
            return {**root, "outcome": "PREPARED", "command": {"schemaId": "CREATE_DEVICE:command:1",
                    "schemaVersion": "1", "payload": payload}}
        if phase == "question":
            # The question's structured missing field selects the consultation.
            return {**root, "outcome": "QUESTION", "question": {"text": "Какой текст показать сверху?",
                    "missingFields": ["name"], "choices": []}}
        if phase == "fail":
            return {**root, "outcome": "UNSUPPORTED", "diagnostic": {"category": "INVALID_PLAN",
                    "code": "FIXTURE_PARTIAL_FAILURE", "message": "Controlled planning failure; saved draft must remain unchanged",
                    "fieldPaths": [], "retriable": False}}
        intent = current["intentId"]
        if phase == "chain_dashboard":
            assert intent == "CREATE_DASHBOARD"
            assert current["contract"]["resolvedInputs"]["sourceId"] == self.chain_source_id
            workflow = next(x["data"]["context"] for x in current["loadResults"] if x["loaderId"] == "workflow.context")
            assert workflow.get("selectedDashboardSourceRef")
            return {**root, "outcome": "PREPARED", "command": {"schemaId": intent + ":command:2",
                    "schemaVersion": "2", "payload": {"protocol": "dashboard-operations-v1", "dashboardPeriod": "LAST_1_HOUR",
                    "operations": [{"id": "name", "op": "rename_dashboard", "name": self.created_name + " chain"},
                                   {"id": "note", "op": "add_note", "title": "Waiting for data", "text": "Fixture source"}]}}}
        if phase == "refine":
            assert intent == "REFINE_DASHBOARD_REVIEW"
            return {**root, "outcome": "PREPARED", "command": {"schemaId": intent + ":command:2",
                    "schemaVersion": "2", "payload": {"protocol": "dashboard-operations-v1", "operations": [
                        {"id": "name", "op": "rename_dashboard", "name": self.created_name + " refined"}]}}}
        if phase == "widget_edit":
            assert intent == "UPDATE_DASHBOARD_DRAFT"
            base = next(x["data"] for x in current["loadResults"] if x["loaderId"] == "review.base")
            inventory = base["actions"][0]["currentWidgetInventory"]["widgets"]
            speed, = [widget["key"] for widget in inventory if widget["role"] == "CURRENT"]
            note, = [widget["id"] for widget in base["actions"][0]["parameters"]["definition"]["widgets"]
                     if widget["type"] == "context-note"]
            workflow = next(x["data"]["context"] for x in current["loadResults"] if x["loaderId"] == "workflow.context")
            data = next(x for x in workflow["dashboardDataChoices"] if x.get("key") == "speedKmh"
                        and any(view["widgetType"] == "time-series" for view in
                                x.get("views", workflow.get("dashboardViewSets", {}).get(x.get("viewsRef"), []))))
            return {**root, "outcome": "PREPARED", "command": {"schemaId": intent + ":command:2",
                    "schemaVersion": "2", "payload": {"protocol": "dashboard-operations-v1", "operations": [
                        {"id": "history", "op": "show_history", "dataRef": data["dataRef"],
                         "widgetKey": None, "view": None, "period": None},
                        {"id": "move", "op": "place_widgets", "targets": ["@history"], "position": "TOP", "anchor": None},
                        {"id": "size", "op": "resize_widgets", "targets": [speed], "width": "FULL", "height": 2,
                         "heightPercent": None},
                        {"id": "settings", "op": "set_presentation", "targets": [speed],
                         "settings": [{"key": "showTitle", "value": False}]},
                        {"id": "remove", "op": "remove_widgets", "targets": [note]}]}}}
        if phase == "undo":
            assert intent == "UPDATE_DASHBOARD_DRAFT"
            return {**root, "outcome": "PREPARED", "command": {"schemaId": intent + ":command:2",
                    "schemaVersion": "2", "payload": {"protocol": "dashboard-operations-v1",
                    "operations": [{"id": "undo", "op": "restore_review", "restoreSteps": 1}]}}}
        operations = [{"id": "name", "op": "rename_dashboard",
                       "name": self.created_name if phase in ("create", "typed_create", "pair") else self.edited_name}]
        payload = {"protocol": "dashboard-operations-v1", "operations": operations}
        if phase in ("create", "typed_create", "pair"):
            assert intent == "CREATE_DASHBOARD"
            if phase == "typed_create":
                assert current["contract"]["resolvedInputs"]["name"] == self.created_name
            workflow = next(x["data"]["context"] for x in current["loadResults"] if x["loaderId"] == "workflow.context")
            speed = next(x for x in workflow["dashboardDataChoices"] if x.get("key") == "speedKmh")
            operations.extend([{"id": "speed", "op": "show_current", "dataRef": speed["dataRef"]},
                               {"id": "note", "op": "add_note", "title": "Synthetic fixture", "text": "Recorded test observations"}])
            if phase == "pair":
                other = next(x for x in workflow["dashboardDataChoices"]
                             if x.get("key") == "speedKmh" and x["deviceRef"] != speed["deviceRef"])
                operations.append({"id": "otherSpeed", "op": "show_current", "dataRef": other["dataRef"]})
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
    chain_dashboard = None
    switched_dashboard = None
    paired_dashboard = None
    switch_fixture = None
    created_devices = []
    location = None
    running_simulator = None
    created_rule = None
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

    def turn(chat, text, phases, base=None, continue_step=None, name=None, context=None):
        detail = client.call("GET", "/ai-agent/conversations/" + chat)
        body = {"requestId": str(uuid.uuid4()), "expectedRevision": detail["conversation"]["revision"],
                "text": text, "context": {"deviceIds": fixture.devices} if context is None else context,
                "providerId": provider["id"], "modelConfigId": model["id"]}
        if base:
            body["parentPlanId"] = base
        if continue_step:
            body["continueStepId"] = continue_step
        if name is not None:
            body["context"]["name"] = name
        server.expect(*phases)
        result = client.call("POST", f"/ai-agent/conversations/{chat}/messages", body)
        trace = client.call("GET", f"/ai-agent/conversations/{chat}/turns/{body['requestId']}/trace/export")
        report.setdefault("turns", []).append({"conversationId": chat, "requestId": body["requestId"],
                    "status": result.get("pendingStatus"), "modelCalls": sum(e.get("stage") == "MODEL_CALL" and e.get("status") == "STARTED" for e in trace),
                    "failures": [{"stage": e.get("stage"), "code": e.get("code"),
                                  "validation": (e.get("details") or {}).get("validation")}
                                 for e in trace if e.get("status") == "FAILED"]})
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
        if args.reviewed_table:
            language = client.call("GET", "/data/language")
            server.registry_digest = language["registry"]["registryDigest"]
            label = marker + " table"
            server.table_command = {"label": label, "history": False, "fields": [
                {"key": "cabinet", "label": "Cabinet", "type": "TEXT", "nullable": False, "unit": "1",
                 "indexed": True, "businessKey": True},
                {"key": "doorOpen", "label": "Door open", "type": "BOOLEAN", "nullable": True, "unit": "1",
                 "indexed": False, "businessKey": False}]}
            reviewed, _ = turn(conversation(), f'Create a native table "{label}" with a text cabinet key '
                               "and a nullable Boolean door status.", ["plan_table", "table"], context={})
            plan = reviewed["plan"]
            require(plan["planningStatus"] == "READY", "TABLE_REVIEW_NOT_READY")
            action, = plan["actions"]
            parameters = action["parameters"]
            document = parameters["document"]
            dataset_id = parameters["datasetId"]
            report["isolatedNativeDatasetId"] = dataset_id
            dataset_url = f"/data/datasets/{dataset_id}"
            check("table_preview_pins_vcl_schema", action["requiresConfirmation"] and document["language"] == "VCL/1"
                  and document["kind"] == "COLLECTION" and document["spec"]["id"] == dataset_id
                  and document["spec"]["label"] == label
                  and [field["type"] for field in document["spec"]["fields"]] == ["TEXT", "BOOLEAN"]
                  and document["spec"]["keyFieldIds"] == [document["spec"]["fields"][0]["id"]])
            try:
                client.call("GET", dataset_url)
            except ApiError as error:
                check("table_preview_has_no_dataset", error.status == 404)
            else:
                check("table_preview_has_no_dataset", False)
            execution = execute(plan)
            require(execution["status"] == "SUCCESS", "TABLE_CONFIRMATION_FAILED")
            receipt = execution["actions"][0]["result"]
            applied = client.call("GET", dataset_url)
            saved_schema = {key: value for key, value in applied["definition"].items()
                            if key not in {"generation", "dataVersion", "systemFields"}}
            report["nativeTableReadback"] = {
                "review": {"document": document, "ownerReviewId": parameters["ownerReviewId"],
                           "ownerReceiptId": parameters["ownerReceiptId"]},
                "execution": receipt, "savedSchema": saved_schema, "savedSchemaDigest": applied["schemaDigest"],
            }
            check("table_receipt_matches_review", receipt["datasetId"] == dataset_id
                  and receipt["ownerReviewId"] == parameters["ownerReviewId"]
                  and receipt["receiptId"] == parameters["ownerReceiptId"])
            check("table_schema_matches_owner", receipt["schemaDigest"] == applied["schemaDigest"]
                  and applied["schemaRevision"] == 1 and saved_schema == document["spec"])
            check("table_confirmation_replays_receipt", execute(plan)["actions"][0]["result"] == receipt)
            manual_key = marker + "-manual-table"
            field_keys = [field["key"] for field in server.table_command["fields"]]
            manual = client.call("POST", "/data/review-reservations",
                                 {"kind": "COLLECTION", "fieldKeys": field_keys, "idempotencyKey": manual_key})
            manual_document = json.loads(json.dumps(document))
            manual_spec = manual_document["spec"]
            manual_spec["id"] = manual["resourceId"]
            field_ids = {field["id"]: manual["fieldIdsByKey"][key]
                         for field, key in zip(manual_spec["fields"], field_keys, strict=True)}
            for field in manual_spec["fields"]:
                field["id"] = field_ids[field["id"]]
            manual_spec["keyFieldIds"] = [field_ids[field] for field in manual_spec["keyFieldIds"]]
            manual_spec["storage"]["indexedFieldIds"] = [
                field_ids[field] for field in manual_spec["storage"]["indexedFieldIds"]]
            invalid = {**manual_document, "spec": {**manual_spec, "unknownField": True}}
            try:
                client.call("POST", "/data/dataset-reviews",
                            {"reservationId": manual["reviewId"], "document": invalid})
            except ApiError as error:
                check("manual_native_owner_rejects_unknown_vcl_field", error.status == 400)
            else:
                check("manual_native_owner_rejects_unknown_vcl_field", False)
            manual_review = client.call("POST", "/data/dataset-reviews",
                                        {"reservationId": manual["reviewId"], "document": manual_document})
            check("manual_and_ai_use_same_native_owner_contract", manual_review["typedDiff"] == manual_spec)
        if args.reviewed_mapping:
            require(profile.get("sourceMappingEnabled"), "SOURCE_MAPPING_PROFILE_NOT_ACTIVE")
            device = client.call("POST", "/devices", {"name": marker + " mapping", "type": "VEHICLE"},
                                 headers={"Idempotency-Key": marker + "-mapping"})
            device_id = device["id"]
            created_devices.append(device_id)
            sources_url = f"/devices/{device_id}/data-sources"
            source = client.call("POST", sources_url, {
                "sourceType": "CUSTOM", "protocol": "REST", "externalId": marker + "-mapping",
                "mapping": {"telemetryPolicy": {"fields": [
                    {"path": "doorOpen", "key": "doorOpen", "label": "Door open", "type": "string",
                     "unit": "1", "store": True, "display": True}]}},
                "adapterConfig": {}, "credentialReferences": {}})
            binding_id = source["id"]
            binding_url = f"/data/source-bindings/{binding_id}"
            base = client.call("GET", binding_url + "?schemaRevision=1")
            field, = base["definition"]["fieldMappings"]
            conversion = {"method": "parse_boolean", "trueTokens": ["open"], "falseTokens": ["closed"]}
            server.mapping_command = {"fieldId": field["fieldId"], "sample": "open", "conversion": conversion}
            reviewed, _ = turn(conversation(), "Map Door open using open as true and closed as false. Preview open.",
                               ["plan_mapping", "mapping"], context={"sourceBinding": f"{binding_id}:1"})
            plan = reviewed["plan"]
            require(plan["planningStatus"] == "READY", "MAPPING_REVIEW_NOT_READY")
            action, = plan["actions"]
            parameters = action["parameters"]
            digest = parameters["mappingDigest"]
            check("mapping_preview_exact", action["requiresConfirmation"]
                  and parameters["bindingId"] == binding_id and parameters["schemaRevision"] == 1
                  and parameters["fieldId"] == field["fieldId"] and parameters["conversion"] == conversion
                  and parameters["mappingExample"] == {"sample": "open", "literal": {"type": "BOOLEAN", "value": True}})
            try:
                client.call("POST", f"/ai-agent/plans/{plan['planId']}/execute",
                            {"confirmed": False, "expectedVersion": plan["version"], "payloadDigest": plan["payloadDigest"]})
            except ApiError as error:
                check("mapping_requires_confirmation", error.status == 409)
            else:
                check("mapping_requires_confirmation", False)
            check("mapping_preview_keeps_source_unchanged", client.call("GET", binding_url) == base)
            execution = execute(plan)
            require(execution["status"] == "SUCCESS", "MAPPING_CONFIRMATION_FAILED")
            receipt = execution["actions"][0]["result"]
            applied = client.call("GET", binding_url)
            check("mapping_receipt_matches_owner", receipt == client.call(
                "GET", f"{binding_url}/mapping-receipts/{digest}?schemaRevision=1")
                  and receipt["bindingId"] == binding_id and receipt["mappingDigest"] == digest
                  and receipt["fieldId"] == field["fieldId"] and receipt["schemaRevision"] == 2
                  and receipt["schemaSnapshotId"] == applied["snapshotId"]
                  and receipt["definitionDigest"] == applied["definitionDigest"])
            updated_field, = applied["definition"]["fieldMappings"]
            historical = client.call("GET", binding_url + "?schemaRevision=1")
            saved_sources = client.call("GET", sources_url)
            saved_source, = [item for item in saved_sources if item["id"] == binding_id]
            check("mapping_saved_exactly_once", updated_field["sourceType"] == "boolean"
                  and updated_field["fieldId"] == field["fieldId"]
                  and saved_source["mapping"]["reviewedBooleanMappings"]["doorOpen"] == conversion
                  and execute(plan)["actions"][0]["result"] == receipt
                  and client.call("GET", binding_url) == applied
                  and historical["currentSchemaRevision"] == 2 and historical["schemaRevision"] == 1
                  and historical["snapshotId"] == base["snapshotId"]
                  and historical["definitionDigest"] == base["definitionDigest"]
                  and historical["definition"] == base["definition"])
        chat = conversation()
        asked, _ = turn(chat, "Для выбранного устройства создай дашборд с глобальным периодом «Последний час». Сначала уточни название.",
                        ["plan_create", "question", "consult_question"])
        check("persisted_clarification", asked["plan"]["planningStatus"] == "NEEDS_INPUT")
        if args.restart_service:
            restart()
        restored = client.call("GET", "/ai-agent/conversations/" + chat)
        check("question_survives_restart", restored["orchestration"]["pendingQuestion"] == asked["orchestration"]["pendingQuestion"])
        plan_id = restored["orchestration"]["planId"]
        requirements = client.call("GET", f"/ai-agent/plans/{plan_id}/requirements")
        check("typed_question_matches_root", requirements["question"]["id"] ==
              restored["orchestration"]["pendingQuestion"]["questionId"])
        prepared, request = turn(chat, server.created_name, ["create"])
        check("ordinary_resume_does_not_call_specialist", report["turns"][-1]["modelCalls"] == 1)
        cleared = client.call("GET", f"/ai-agent/plans/{plan_id}/requirements")
        check("legacy_answer_closes_typed_question", cleared.get("question") is None)
        typed_chat = conversation()
        typed_asked, _ = turn(typed_chat, "Создай дашборд и уточни название.", ["plan_create", "question", "consult_question"])
        typed_id = typed_asked["orchestration"]["planId"]
        typed_url = f"/ai-agent/plans/{typed_id}/requirements"
        snapshot = client.call("GET", typed_url)
        waiting_tasks = client.call("GET", f"/ai-agent/plans/{typed_id}/consultations")
        check("specialist_waiting_for_name", len(waiting_tasks) == 1 and waiting_tasks[0]["status"] == "WAITING_USER")
        answer = {"planId": typed_id, "requestId": str(uuid.uuid4()), "questionId": snapshot["question"]["id"],
                  "expectedQuestionRevision": snapshot["question"]["revision"],
                  "expectedRequirementsRevision": snapshot["revision"],
                  "answer": {"kind": "TEXT", "text": server.created_name}}
        calls_before_answer = server.calls
        answered = client.call("POST", typed_url + "/answers", answer)
        check("typed_answer_confirmed_without_model", answered["question"] is None
              and answered["slots"][snapshot["question"]["slots"][0]]["value"]["value"] == server.created_name
              and server.calls == calls_before_answer)
        check("typed_answer_replay", client.call("POST", typed_url + "/answers", answer) == answered)
        continued, _ = turn(typed_chat, "Продолжи подготовку того же дашборда.", ["consult_name", "typed_create"],
                            continue_step="dashboard")
        check("typed_answer_used_in_same_plan", continued["orchestration"]["planId"] == typed_id
              and continued["plan"]["planningStatus"] == "READY"
              and definition(continued["plan"])["name"] == server.created_name)
        tasks = client.call("GET", f"/ai-agent/plans/{typed_id}/consultations")
        check("registered_specialist_resumed_same_task", len(tasks) == 1 and tasks[0]["status"] == "SUCCEEDED"
              and tasks[0]["id"] == waiting_tasks[0]["id"] and tasks[0]["exchanges"] == 2)
        for branch, phases, expected in (
            ("delegate", ["consult_delegate", "consult_child", "consult_join"], [1, 2]),
            ("load", ["consult_load", "consult_loaded"], [2]),
        ):
            extra, _ = turn(conversation(), "Prepare a dashboard using the supplied name.",
                            ["plan_create", *phases, "typed_create"], name=server.created_name)
            extra_plan = extra["orchestration"]["planId"]
            tasks = client.call("GET", f"/ai-agent/plans/{extra_plan}/consultations")
            check("specialist_" + branch, extra["plan"]["planningStatus"] == "READY"
                  and sorted(task["exchanges"] for task in tasks) == expected
                  and all(task["status"] == "SUCCEEDED" for task in tasks))
        plan = prepared["plan"]
        require(plan["planningStatus"] == "READY", "CREATE_REVIEW_NOT_READY")
        reviewed = definition(plan)
        check("create_review_has_requested_global_period",
              reviewed.get("defaultContext", {}).get("time", {}).get("preset") == "LAST_1_HOUR")
        calls = server.calls
        replay = client.call("POST", f"/ai-agent/conversations/{chat}/messages", request)
        check("turn_replay_without_model", server.calls == calls and definition(replay["plan"]) == reviewed)
        try:
            client.call("POST", f"/ai-agent/plans/{plan['planId']}/execute", {"confirmed": True, "expectedVersion": 999,
                        "payloadDigest": plan["payloadDigest"]})
            raise AssertionError("STALE_CONFIRMATION_EXECUTED")
        except ApiError as error:
            check("stale_confirmation_denied", error.status == 409)
        if args.reviewed_refinement:
            result, _ = turn(conversation(), "Change only the name in this unconfirmed proposal.",
                             ["plan_refine", "refine"], plan["planId"])
            refinement = result["plan"]
            expected_refinement = deepcopy(reviewed)
            expected_refinement["name"] += " refined"
            check("refinement_is_new_exact_review", refinement["planningStatus"] == "READY"
                  and refinement["planId"] != plan["planId"] and definition(refinement) == expected_refinement)
            check("refinement_waits_for_confirmation", not client.call("GET", "/dashboards?limit=100&status=ALL")["items"])
            plan, reviewed, calls = refinement, expected_refinement, server.calls
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
        check("update_waits_for_confirmation",
              client.call("GET", f"/dashboards/{dashboard}/draft")["definition"] == reviewed)
        updated = execute(update)
        check("same_id_edit", updated["status"] == "SUCCESS" and updated["actions"][0]["result"]["dashboardId"] == dashboard)
        check("exact_update_readback", client.call("GET", f"/dashboards/{dashboard}/draft")["definition"] == expected)
        check("update_receipt_replay", execute(update)["actions"][0]["result"]["dashboardId"] == dashboard)
        if args.reviewed_widget_edit:
            result, _ = turn(conversation(), "Add speed history at the top, enlarge the current card, hide its title and remove the note.",
                             ["plan_edit", "widget_edit"], update["planId"])
            widget_review = result["plan"]
            check("widget_edit_ready", widget_review["planningStatus"] == "READY")
            proposed = definition(widget_review)
            speed = deepcopy(next(widget for widget in expected["widgets"] if widget["type"] == "latest-value"))
            speed["presentation"]["showTitle"] = False
            history, = [widget for widget in proposed["widgets"] if widget["type"] == "time-series"]
            check("widget_edit_changes_only_speed_title_setting_and_removes_note",
                  len(proposed["widgets"]) == 2 and speed in proposed["widgets"])
            desktop = sorted(proposed["layouts"]["desktop"], key=lambda item: (item["y"], item["x"]))
            check("widget_edit_order_and_size", [item["widgetId"] for item in desktop] == [history["id"], speed["id"]]
                  and (desktop[1]["w"], desktop[1]["h"]) == (12, 2))
            check("widget_edit_same_dashboard", execute(widget_review)["actions"][0]["result"]["dashboardId"] == dashboard)
            check("widget_edit_exact_readback", client.call("GET", f"/dashboards/{dashboard}/draft")["definition"] == proposed)
        if args.reviewed_undo:
            undone, _ = turn(conversation(), "Restore the previous saved dashboard version.",
                             ["plan_edit", "undo"], update["planId"])
            undo = undone["plan"]
            check("undo_review_matches_history", undo["planningStatus"] == "READY" and definition(undo) == reviewed)
            check("undo_waits_for_confirmation", client.call("GET", f"/dashboards/{dashboard}/draft")["definition"] == expected)
            restored = execute(undo)
            check("undo_same_dashboard", restored["status"] == "SUCCESS"
                  and restored["actions"][0]["result"]["dashboardId"] == dashboard)
            check("undo_exact_readback", client.call("GET", f"/dashboards/{dashboard}/draft")["definition"] == reviewed)
            check("undo_receipt_replay", execute(undo)["actions"][0]["result"]["dashboardId"] == dashboard)
        page = client.call("GET", "/dashboards?limit=100&status=ALL")
        check("one_dashboard_only", not page.get("hasNext") and [x["id"] for x in page["items"]] == [dashboard])
        report["reviewedDefinition"], report["updatedDefinition"] = reviewed, expected
        if args.reviewed_read_only:
            device_id = fixture.devices[0]
            parameters = {"deviceId": device_id, "metric": "speedKmh",
                          "from": "2026-01-01T10:00:00Z", "to": "2026-01-01T11:00:00Z"}
            server.simulator_command = ("OPEN_TELEMETRY_WINDOW", parameters)
            read_chat = conversation()
            opened, _ = turn(read_chat, "Open the specified telemetry window.", ["plan_simulator", "simulator"],
                             context={"deviceId": device_id})
            review = opened["plan"]
            require(review["planningStatus"] == "READY", "TELEMETRY_REVIEW_NOT_READY")
            result = execute(review)
            require(result["status"] == "SUCCESS", "TELEMETRY_WINDOW_FAILED")
            route = urlsplit(result["actions"][0]["result"]["route"])
            check("telemetry_route_keeps_exact_window", route.path == f"/devices/{device_id}"
                  and parse_qs(route.query) == {key: [value] for key, value in
                    {"tab": "telemetry", **{key: parameters[key] for key in ("metric", "from", "to")}}.items()})
            foreign_credentials = {"email": "foreign-" + uuid.uuid4().hex + "@example.test",
                                   "password": "Workflow!" + uuid.uuid4().hex}
            anonymous.call("POST", "/auth/register", foreign_credentials)
            foreign_login = anonymous.call("POST", "/auth/login", foreign_credentials)
            foreign = Client(args.gateway, foreign_login["accessToken"], timeout=15)
            foreign.call("GET", "/accounts/current")
            for key, method, path, payload in (
                ("conversation", "GET", f"/ai-agent/conversations/{read_chat}", None),
                ("review", "GET", f"/ai-agent/plans/{review['planId']}", None),
                ("confirmation", "POST", f"/ai-agent/plans/{review['planId']}/execute",
                 {"confirmed": True, "expectedVersion": review["version"], "payloadDigest": review["payloadDigest"]}),
            ):
                try:
                    foreign.call(method, path, payload)
                    raise AssertionError("FOREIGN_" + key.upper() + "_ACCEPTED")
                except ApiError as error:
                    check(f"foreign_{key}_denied", error.status in (403, 404))
            check("owner_receipt_survives_foreign_attempts", execute(review) == result)
        if args.reviewed_rules:
            def reviewed_rule(intent, parameters):
                server.simulator_command = (intent, parameters)
                context = {"deviceId": parameters["deviceId"]} if "deviceId" in parameters else {}
                result, _ = turn(conversation(), f"Prepare {intent} for the fixture.", ["plan_simulator", "simulator"],
                                 context=context)
                require(result["plan"]["planningStatus"] == "READY", intent + "_REVIEW_NOT_READY")
                execution = execute(result["plan"])
                require(execution["status"] == "SUCCESS", intent + "_EXECUTION_FAILED")
                return execution["actions"][0]["result"]

            parameters = {"name": "Rule " + marker, "metric": "activePowerW", "ruleType": "THRESHOLD",
                          "conditionType": "ABOVE_THRESHOLD", "threshold": 100, "severity": "WARNING", "enabled": False}
            result = reviewed_rule("CREATE_ALERT_RULE", {**parameters, "deviceId": fixture.devices[0]})
            rule_id = result["ruleId"]
            created_rule = rule_id
            rule_path = f"/alert-rules/{rule_id}"
            created = client.call("GET", rule_path)
            check("rule_exact_owner_readback", all(created.get(key) == value for key, value in parameters.items())
                  and created["scopeType"] == "DEVICE" and created["scopeId"] == fixture.devices[0])
            result = reviewed_rule("UPDATE_ALERT_RULE", {"ruleId": rule_id, "threshold": 125})
            updated_rule = client.call("GET", rule_path)
            check("rule_update_preserves_identity_and_scope", result["ruleId"] == rule_id
                  and updated_rule["threshold"] == 125 and updated_rule["scopeId"] == fixture.devices[0]
                  and updated_rule["enabled"] is False)
            preview = reviewed_rule("TEST_ALERT_RULE", {key: value for key, value in parameters.items() if key != "enabled"})
            check("rule_preview_is_read_only", "ruleTest" in preview and client.call("GET", rule_path) == updated_rule)
        if args.reviewed_simulator:
            device_id = client.call("POST", "/devices", {"name": "Simulator " + marker, "type": "FRIDGE"},
                                    {"Idempotency-Key": str(uuid.uuid4())})["id"]
            created_devices.append(device_id)
            source_label = "sim-" + uuid.uuid4().hex
            source_path = f"/devices/{device_id}/data-sources"
            source_id = client.call("POST", source_path,
                                    {"sourceType": "SIMULATOR", "protocol": "REST", "externalId": source_label})["id"]
            yaml = client.call("GET", f"/simulation/templates/FRIDGE?sourceId={source_label}")["yamlConfig"]
            config_path = f"/simulation/devices/{device_id}/config"

            def simulator_action(intent, **parameters):
                server.simulator_command = (intent, {"deviceId": device_id, **parameters})
                result, _ = turn(conversation(), f"Prepare {intent} for this device.",
                                 ["plan_simulator", "simulator"], context={"deviceId": device_id})
                require(result["plan"]["planningStatus"] == "READY", intent + "_REVIEW_NOT_READY")
                execution = execute(result["plan"])
                require(execution["status"] == "SUCCESS", intent + "_EXECUTION_FAILED")
                return execution["actions"][0]["result"]

            validation = simulator_action("VALIDATE_SIMULATOR_YAML", yamlConfig=yaml)
            check("simulator_validation_passed", validation["validation"]["valid"])
            try:
                client.call("GET", config_path)
                raise AssertionError("VALIDATION_CREATED_CONFIG")
            except ApiError as error:
                check("simulator_validation_is_read_only", error.status == 404)
            config = {"dataSourceId": source_id, "name": "Fixture simulator", "profile": "FRIDGE",
                      "protocol": "HTTP", "yamlConfig": yaml}
            token = client.call("POST", f"{source_path}/{source_id}/token")["token"]
            client.call("PUT", config_path, {**config, "sourceToken": token})
            del token
            simulator_action("CREATE_SIMULATOR_CONFIG", dataSourceId=source_id, profile="FRIDGE", protocol="HTTP", yamlConfig=yaml)
            saved = client.call("GET", config_path)
            check("simulator_config_readback", saved["dataSourceId"] == source_id and saved["yamlConfig"] == yaml
                  and saved["hasSourceToken"])
            running_simulator = device_id
            started = simulator_action("START_SIMULATION")["simulationRun"]
            check("simulator_started", started["status"] == "RUNNING"
                  and client.call("GET", f"/simulation/devices/{device_id}/status")["runId"] == started["runId"])
            stopped = simulator_action("STOP_SIMULATION")["simulationRun"]
            check("simulator_stopped_same_run", stopped["status"] == "STOPPED" and stopped["runId"] == started["runId"])
            running_simulator = None
        if args.reviewed_device_switch:
            switch_fixture = VehicleFixture(client, marker + "_switch", profile="pair")
            switch_fixture.create()
            first_device, second_device = switch_fixture.devices
            server.switch_device_label = client.call("GET", f"/devices/{second_device}")["name"]
            paired, _ = turn(chat, "Create a speed dashboard for both selected devices.",
                             ["plan_pair", "pair"], context={"deviceIds": [first_device, second_device]})
            pair_review = paired["plan"]
            pair_definition = definition(pair_review)
            check("pair_review_uses_both_devices", pair_review["planningStatus"] == "READY"
                  and set(pair_definition["defaultContext"]["scope"]["deviceIds"]) == {first_device, second_device}
                  and {tuple(widget["binding"]["deviceIds"]) for widget in pair_definition["widgets"]
                       if widget["type"] == "latest-value"} == {(first_device,), (second_device,)})
            pair_result = execute(pair_review)
            require(pair_result["status"] == "SUCCESS", "PAIR_DASHBOARD_FAILED")
            paired_dashboard = pair_result["actions"][0]["result"]["dashboardId"]
            check("pair_exact_owner_readback",
                  client.call("GET", f"/dashboards/{paired_dashboard}/draft")["definition"] == pair_definition)
            switched, _ = turn(chat, f"Create a new speed dashboard for {server.switch_device_label} only.",
                               ["plan_switch", "create"], context={"deviceIds": [first_device, second_device]})
            switch_review = switched["plan"]
            proposed = definition(switch_review)
            check("explicit_device_switch_review", switch_review["planningStatus"] == "READY"
                  and proposed["defaultContext"]["scope"]["deviceIds"] == [second_device]
                  and all(widget["binding"]["deviceIds"] == [second_device] for widget in proposed["widgets"]))
            saved = execute(switch_review)
            require(saved["status"] == "SUCCESS", "SWITCH_DASHBOARD_FAILED")
            switched_dashboard = saved["actions"][0]["result"]["dashboardId"]
            check("explicit_device_switch_owner_readback", switched_dashboard != dashboard
                  and client.call("GET", f"/dashboards/{switched_dashboard}/draft")["definition"] == proposed
                  and client.call("GET", f"/dashboards/{paired_dashboard}/draft")["definition"] == pair_definition)
        if args.reviewed_chain:
            server.device_proposal = {"name": "Chain " + marker, "type": "VEHICLE"}
            chain = conversation()
            first, _ = turn(chain, "Create a vehicle, connect HSL and prepare a dashboard.", ["plan_chain", "device"], context={})
            device_result = execute(first["plan"])
            require(device_result["status"] == "SUCCESS", "CHAIN_DEVICE_FAILED")
            device_id = device_result["actions"][0]["result"]["deviceId"]
            created_devices.append(device_id)
            source_review, _ = turn(chain, "Prepare the source step.", ["source"], continue_step="source", context={})
            source_result = execute(source_review["plan"])
            require(source_result["status"] == "SUCCESS", "CHAIN_SOURCE_FAILED")
            source_receipt = source_result["actions"][0]["result"]
            server.chain_source_id = source_receipt["dataSourceId"]
            check("chain_source_uses_created_device", source_receipt["deviceId"] == device_id)
            loaded = client.call("GET", f"/ai-agent/conversations/{chain}")
            check("chain_reload_retains_plan", loaded["orchestration"]["planId"] == first["orchestration"]["planId"])
            final, _ = turn(chain, "Prepare the dashboard step.", ["chain_dashboard"], continue_step="dashboard", context={})
            chain_review = final["plan"]
            check("chain_dashboard_uses_created_device", definition(chain_review)["defaultContext"]["scope"]["deviceIds"] == [device_id])
            result = execute(chain_review)
            require(result["status"] == "SUCCESS", "CHAIN_DASHBOARD_FAILED")
            chain_dashboard = result["actions"][0]["result"]["dashboardId"]
            check("chain_dashboard_exact_readback",
                  client.call("GET", f"/dashboards/{chain_dashboard}/draft")["definition"] == definition(chain_review))
        if args.reviewed_device:
            location = client.call("POST", "/locations", {"name": "Workshop " + marker})["id"]
            catalogue = sorted({entry["code"] for entry in client.call("GET", "/device-types")})
            require("VEHICLE" in catalogue and len(catalogue) <= 100, "UNEXPECTED_DEVICE_CATALOGUE")
            report["reviewedDeviceTypes"] = catalogue
            server.call_limit += 2 * len(catalogue)
            for index, code in enumerate(catalogue, start=1):
                server.device_proposal = {"name": f"Device {index} {marker}", "type": code}
                context = {}
                if code == "VEHICLE":
                    server.device_proposal.update(locationId=location, manufacturer="Fixture", model="A5")
                    context["locationId"] = location
                result, _ = turn(conversation(), f"Create a device of catalogue type {code}",
                                 ["plan_device", "device"], context=context)
                review = result["plan"]
                check(f"device_review_ready_{code}", review["planningStatus"] == "READY")
                executed = execute(review)
                require(executed["status"] == "SUCCESS", f"DEVICE_CREATION_FAILED_{code}")
                device_id = executed["actions"][0]["result"]["deviceId"]
                created_devices.append(device_id)
                saved_device = client.call("GET", f"/devices/{device_id}")
                fields = ("name", "type", "locationId", "manufacturer", "model")
                check(f"exact_device_readback_{code}",
                      all(saved_device.get(key) == server.device_proposal.get(key) for key in fields))
                check(f"device_receipt_replay_{code}", execute(review)["actions"][0]["result"]["deviceId"] == device_id)
                if args.reviewed_source and code == "VEHICLE":
                    source_path = f"/devices/{device_id}/data-sources"
                    result, _ = turn(conversation(), "Connect the selected vehicle to the HSL fixture.",
                                     ["plan_source", "source"], context={"deviceId": device_id})
                    source_review = result["plan"]
                    check("source_waits_for_confirmation", source_review["planningStatus"] == "READY"
                          and not client.call("GET", source_path))
                    execution = execute(source_review)
                    check("source_confirmed", execution["status"] == "SUCCESS")
                    receipt = execution["actions"][0]["result"]
                    saved_source, = client.call("GET", source_path)
                    check("source_owner_readback", receipt["deviceId"] == device_id
                          and saved_source["id"] == receipt["dataSourceId"]
                          and saved_source["externalId"] == f"hsl-bus-{server.hsl_key}")
                    check("source_receipt_replay", execute(source_review)["actions"][0]["result"]["dataSourceId"] == saved_source["id"]
                          and len(client.call("GET", source_path)) == 1)
        report["status"] = "PASSED"
    finally:
        failures = []
        if created_rule:
            try:
                client.call("DELETE", f"/alert-rules/{created_rule}")
            except ApiError as error:
                failures.append({"resource": "reviewed_rule", "status": error.status})
        if running_simulator:
            try:
                client.call("POST", f"/simulation/devices/{running_simulator}/stop")
            except ApiError as error:
                failures.append({"resource": "simulation_run", "status": error.status})
        for device_id in created_devices:
            try:
                client.call("POST", f"/devices/{device_id}/decommission")
            except ApiError as error:
                failures.append({"resource": "reviewed_device", "status": error.status})
        if location:
            try:
                client.call("DELETE", f"/locations/{location}")
            except ApiError as error:
                failures.append({"resource": "reviewed_location", "status": error.status})
        for saved_dashboard in filter(None, (dashboard, chain_dashboard, paired_dashboard, switched_dashboard)):
            try:
                archive_dashboard(client, saved_dashboard)
            except ApiError as error:
                failures.append({"resource": "dashboard", "status": error.status})
        failures.extend(fixture.cleanup())
        if switch_fixture:
            failures.extend(switch_fixture.cleanup())
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
    parser.add_argument("--reviewed-device", action="store_true")
    parser.add_argument("--reviewed-mapping", action="store_true", help="Verify reviewed VCL source mapping")
    parser.add_argument("--reviewed-table", action="store_true", help="Create a reviewed native table in a disposable account")
    parser.add_argument("--reviewed-source", action="store_true", help="Use an isolated HSL provisioning service")
    parser.add_argument("--reviewed-chain", action="store_true", help="Verify dependent steps with isolated HSL provisioning")
    parser.add_argument("--reviewed-simulator", action="store_true")
    parser.add_argument("--reviewed-rules", action="store_true")
    parser.add_argument("--reviewed-read-only", action="store_true")
    parser.add_argument("--reviewed-device-switch", action="store_true")
    parser.add_argument("--reviewed-undo", action="store_true")
    parser.add_argument("--reviewed-widget-edit", action="store_true")
    parser.add_argument("--reviewed-refinement", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/langgraph4j-docker-workflow/summary.json")
    args = parser.parse_args()
    if args.reviewed_source:
        args.reviewed_device = True
    if args.reviewed_undo and args.reviewed_widget_edit:
        parser.error("Run the undo and widget edit cases separately")
    require(ROOT / "artifacts" in args.output.resolve().parents, "OUTPUT_MUST_BE_IGNORED_ARTIFACT")
    report = {"status": "RUNNING", "scope": "DETERMINISTIC_INTEGRATION_NOT_MODEL_QUALITY", "checks": [], "realModelCalls": 0,
              "harnessHash": sha(Path(__file__))}
    source = ROOT / "backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent"
    report["sourceHashes"] = {p: sha(source / p) for p in ["adapter/langgraph4j/LangGraphAgentRunner.java",
          "orchestration/plan/IntentOrchestration.java", "orchestration/plan/IntentPlanActions.java", "adapter/JdbcIntentPlanStore.java"]}
    server = Provider(args.fixture_port, 22 + 2 * sum(
        (args.reviewed_undo, args.reviewed_widget_edit, args.reviewed_refinement, args.reviewed_source,
         args.reviewed_device_switch, args.reviewed_mapping, args.reviewed_table))
        + 2 * args.reviewed_device_switch + 4 * args.reviewed_chain
        + 8 * args.reviewed_simulator + 6 * args.reviewed_rules + 2 * args.reviewed_read_only)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        for port in [8080, 8081, 8082, 8083, 8084, 8088, 8089]:
            wait_health(port=port)
        if args.reviewed_simulator:
            wait_health(port=8087)
        if args.reviewed_rules:
            wait_health(port=8086)
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
