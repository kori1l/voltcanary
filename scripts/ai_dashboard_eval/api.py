"""Ordinary authenticated HTTP, safe errors and reproducibility metadata."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import threading
import base64
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


class ApiError(Exception):
    def __init__(self, status, code="HTTP_ERROR"):
        self.status, self.code = status, code
        super().__init__(f"HTTP {status}: {code}")


class Client:
    def __init__(self, gateway, token, timeout=600, refresh_token=None):
        self.gateway, self.token, self.timeout = gateway.rstrip("/"), token, timeout
        self.refresh_token = refresh_token
        self.refresh_lock = threading.Lock()

    def _renew(self, previous):
        with self.refresh_lock:
            if self.token == previous:
                if not self.refresh_token:
                    raise ApiError(401, "ASSISTANT_SESSION_REFRESH_REQUIRED")
                refreshed = Client(self.gateway, "", self.timeout).call("POST", "/auth/refresh", {"refreshToken": self.refresh_token})
                self.token, self.refresh_token = refreshed["accessToken"], refreshed["refreshToken"]

    def prepare_planning_session(self):
        # JWT parsing only schedules renewal; the server still validates signature and permissions.
        try:
            payload = self.token.split(".")[1]
            expiry = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))["exp"]
        except (IndexError, ValueError, KeyError, TypeError):
            return
        if isinstance(expiry, (int, float)) and expiry <= time.time() + 300:
            self._renew(self.token)

    def call(self, method, path, body=None, headers=None, token=None, _refresh=True):
        if method == "POST" and not token and (path == "/ai-agent/plans" or path.endswith("/clarifications") or path.startswith("/ai-agent/conversations/") and path.endswith("/messages")):
            self.prepare_planning_session()
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        selected_token = token or self.token
        combined = {"Authorization": "Bearer " + selected_token} if selected_token else {}
        if payload is not None:
            combined["Content-Type"] = "application/json; charset=utf-8"
        combined.update(headers or {})
        request = Request(self.gateway + path, payload, combined, method=method)
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                return json.loads(raw) if raw.strip() else None
        except HTTPError as error:
            if error.code == 401 and not token and _refresh and self.refresh_token:
                error.close()
                self._renew(selected_token)
                return self.call(method, path, body, headers, _refresh=False)
            # Never persist raw upstream messages, headers, credentials or bodies.
            try:
                code = json.loads(error.read()).get("code", "HTTP_ERROR")
            except (ValueError, AttributeError):
                code = "HTTP_ERROR"
            if not isinstance(code, str) or not code.replace("_", "").isalnum():
                code = "HTTP_ERROR"
            raise ApiError(error.code, code[:80]) from None
        except (URLError, TimeoutError, ConnectionError):
            raise ApiError(0, "DEPENDENCY_UNAVAILABLE") from None


def authenticate(gateway, account):
    token = os.environ.get("VOLT_EVAL_ACCESS_TOKEN")
    refresh = os.environ.get("VOLT_EVAL_REFRESH_TOKEN")
    if not token:
        password = os.environ.get("VOLT_EVAL_PASSWORD")
        if not password:
            raise ApiError(0, "AUTH_ENV_REQUIRED")
        login = Client(gateway, "").call("POST", "/auth/login", {
            "email": account["email"], "password": password})
        token = login["accessToken"]
        refresh = login.get("refreshToken")
    client = Client(gateway, token, refresh_token=refresh)
    scope = client.call("GET", "/accounts/current/scope")
    actual = scope.get("id", scope.get("accountId"))
    if str(actual) != account["accountId"]:
        raise ApiError(0, "FIXTURE_ACCOUNT_MISMATCH")
    provider = client.call("GET", "/ai-agent/model-providers/" + account["providerId"])
    model = next((m for m in provider["models"] if m["id"] == account["modelConfigId"]), None)
    if not model or not model["enabled"] or model["modelId"] != account["modelId"]:
        raise ApiError(0, "CONFIGURED_MODEL_MISMATCH")
    for key, expected in account.get("expectedModelSettings", {}).items():
        if model.get(key) != expected:
            raise ApiError(0, "MODEL_SETTINGS_MISMATCH")
    return client, provider


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def fingerprint(corpus):
    service = ROOT / "backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/service"
    hashes = {name: sha(service / name) for name in (
        "DashboardCreationPrompt.java", "DashboardRefinementPrompt.java",
        "DashboardCompositionCompiler.java", "DashboardIntentEdits.java")}
    capability_files = list(service.glob("Dashboard*Reference*.java")) + list(service.glob("DashboardCapability*.java"))
    capability_files += [service / name for name in ("DashboardSourceChoiceRequired.java", "DashboardTaskConversation.java", "DashboardTaskContext.java", "CreationTaskPlanning.java", "PlanClarifications.java")]
    dashboard = ROOT / "backend/services/dashboard-service/src/main/java/com/voltcanary/dashboard"
    capability_files += [dashboard / name for name in ("application/DashboardCapabilityResolution.java",
                        "domain/DashboardDataChoices.java", "domain/WidgetCapabilities.java", "adapter/HttpDashboardCapabilityEvidence.java")]
    hashes["capabilityResolution"] = {str(path.relative_to(ROOT)): sha(path) for path in capability_files if path.exists()}
    operation_files = list(service.glob("DashboardOperation*.java"))
    operation_files += [ROOT / "backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/domain/DashboardOperation.java"]
    hashes["semanticOperations"] = {str(path.relative_to(ROOT)): sha(path) for path in operation_files}
    orchestration = service.parent / "orchestration"
    primary_files = list(service.glob("Primary*.java")) + list((service.parent / "adapter").glob("Primary*.java"))
    primary_files += list(orchestration.rglob("*.java"))
    primary_files += [ROOT / "docs/target/ai-assistant-protocol.schema.json"]
    primary_files += [service / name for name in ("CurrentWidgetInventory.java", "DashboardSkillContract.java", "DashboardSkillGuide.java", "DashboardDesignGuide.java", "DashboardAssessment.java", "DashboardAssessmentGuide.java", "DashboardDraftTarget.java", "DeviceSetupExecutor.java", "ToolExecutor.java", "CreationReconciliation.java")]
    primary_files += [ROOT / "backend/services/dashboard-service/src/main/java/com/voltcanary/dashboard" / name
                      for name in ("application/DashboardDrafts.java", "controller/DashboardDraftController.java")]
    hashes["primaryOrchestration"] = {str(path.relative_to(ROOT)): sha(path) for path in sorted(primary_files)}
    status = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)
    hashes["dirtyTreeStatus"] = hashlib.sha256(status).hexdigest()
    tracked = subprocess.check_output(["git", "-c", "core.safecrlf=false", "diff", "--binary", "HEAD"], cwd=ROOT, stderr=subprocess.DEVNULL)
    hashes["trackedDiff"] = hashlib.sha256(tracked).hexdigest()
    hashes["harness"] = {str(p.relative_to(ROOT)): sha(p) for p in
                         sorted((ROOT / "scripts/ai_dashboard_eval").glob("*.py"))}
    hashes["entryPoint"] = sha(ROOT / "scripts/ai-dashboard-conversation-evaluation.py")
    return {"commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip(),
            "evaluationDriverVersion": "offline-reliability-v1",
            "corpusSha256": sha(corpus), "sourceHashes": hashes,
            "runtimeSourceParity": "NOT_AUTOMATICALLY_PROVEN"}


def wait_until(read, predicate, seconds=45):
    deadline = time.monotonic() + seconds
    while True:
        value = read()
        if predicate(value):
            return value
        if time.monotonic() >= deadline:
            raise ApiError(0, "FIXTURE_NOT_READY")
        time.sleep(1)


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
