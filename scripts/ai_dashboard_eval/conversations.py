"""Run existing independent outcome predicates through the durable chat API."""
import uuid
import threading

from .api import ApiError, write_json
from .runner import Runner
from .call_accounting import from_trace


class ConversationRunner(Runner):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.conversations = {}
        self.diagnostics = []
        self._turn_accounting = threading.local()

    def branch(self, message, plan, devices):
        # Independent branches have separate revision streams and an explicit immutable base.
        self._turn_accounting.independent = True
        try:
            return self.model(message, plan, devices)
        finally:
            self._turn_accounting.independent = False

    def recover(self, plan):
        with self.lock:
            conversation = self.conversations.get(plan["planId"])
        if not conversation:
            return super().recover(plan)
        detail = self.api.call("GET", "/ai-agent/conversations/" + conversation)
        current = detail.get("plan")
        if current and current.get("planningStatus") == "READY":
            return current
        accepted = detail.get("acceptedReview")
        if not accepted:
            raise ApiError(0, "ACCEPTED_REVIEW_UNAVAILABLE")
        # A read-only retained preview is distinct from successful preparation of the failed turn.
        result = dict(accepted)
        result["_evaluationReadOnlyAcceptedReview"] = True
        return result

    def turn_accounting(self, plan, turn):
        return getattr(self._turn_accounting, "value", None) if turn["action"] == "message" else None

    def model(self, message, plan, devices, independent=False):
        independent = independent or getattr(self._turn_accounting, "independent", False)
        self._turn_accounting.value = None
        with self.lock:
            conversation = self.conversations.get(plan.get("planId")) if plan and not independent else None
        if not conversation:
            conversation = str(uuid.uuid4())
            self.api.call("POST", "/ai-agent/conversations", {"conversationId": conversation})
        detail = self.api.call("GET", "/ai-agent/conversations/" + conversation)
        request = str(uuid.uuid4())
        terminal = {"SUCCESS", "FAILED", "PARTIAL_SUCCESS", "RUNNING", "UNKNOWN", "EXPIRED"}
        continuation = plan and plan.get("executionStatus") not in terminal
        task = (plan.get("task") or {}) if continuation else {}
        task_id = task.get("taskId") or str(uuid.uuid4())
        body = {"requestId": request, "expectedRevision": detail["conversation"]["revision"], "text": message,
                "context": {"deviceIds": devices}, "providerId": self.account["providerId"],
                "modelConfigId": self.account["modelConfigId"], "metadata": {"requestId": request,
                    "taskId": task_id, "branchId": str(uuid.uuid4()) if independent else task.get("branchId") or task_id,
                    "expectedTaskRevision": task.get("revision", 0)}}
        if continuation:
            body["parentPlanId"] = plan["planId"]
        transport_error = None
        try:
            response = self.api.call("POST", f"/ai-agent/conversations/{conversation}/messages", body)
        except ApiError as error:
            transport_error = error
            response = {"pendingStatus": "TRANSPORT_FAILURE", "httpStatus": error.status, "errorCode": error.code}
        # All diagnostic reads are ordinary authenticated APIs. Credentials never enter an artifact.
        try:
            events = self.api.call("GET", f"/ai-agent/conversations/{conversation}/turns/{request}/trace/export")
        except ApiError as error:
            events = [{"stage": "DIAGNOSTIC_READ", "status": "UNKNOWN", "code": error.code,
                       "details": {"httpStatus": error.status}}]
        messages, after = [], 0
        while True:
            try:
                page = self.api.call("GET", f"/ai-agent/conversations/{conversation}/messages?after={after}&limit=100")
            except ApiError as error:
                response["historyReadFailure"] = {"httpStatus": error.status, "code": error.code}
                break
            messages.extend(page["items"])
            if not page.get("nextCursor"):
                break
            after = page["nextCursor"]
        target = self.output / "conversations" / conversation
        write_json(target / (request + ".json"), {"detail": response, "trace": events, "messages": messages})
        failures = [{k: event.get(k) for k in ("stage", "status", "code", "details", "attemptId", "modelRunId")}
                    for event in events if event.get("status") in ("FAILED", "UNKNOWN")]
        record = {"conversationId": conversation, "requestId": request, "message": message,
                  "deviceIds": list(devices),
                  "planId": (response.get("plan") or {}).get("planId"), "pendingStatus": response.get("pendingStatus"),
                  "modelCalls": sum(e.get("stage") == "MODEL_CALL" and e.get("status") == "STARTED" for e in events),
                  "failures": failures,
                  "providerEvidence": [{key: event.get(key) for key in ("stage", "status", "code", "details")}
                                       for event in events if event.get("stage") in ("PROVIDER_USAGE", "MODEL_RUN")],
                  "artifact": str((target / (request + ".json")).relative_to(self.output))}
        record["callAccounting"] = from_trace(events)
        self._turn_accounting.value = record["callAccounting"]
        with self.lock:
            self.diagnostics.append(record)
            write_json(self.output / "diagnostics.json", self.diagnostics)
        if transport_error:
            raise transport_error
        if not response.get("plan"):
            raise ApiError(0, "CONVERSATION_TURN_FAILED")
        result = response["plan"]
        result["_evaluationCallAccounting"] = from_trace(events)
        with self.lock:
            self.conversations[result["planId"]] = conversation
        return result
