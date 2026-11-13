"""Isolated revisioned task smoke. Requires an explicitly disposable OpenAI provider."""
import argparse
import json
from pathlib import Path

from ai_dashboard_eval.account_lock import AccountLease
from ai_dashboard_eval.api import ROOT, authenticate, sha, write_json
from ai_dashboard_eval.observer import ProviderObserver
from ai_dashboard_eval.state_smoke import run_state_smoke


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--observe-disposable-provider", action="store_true", required=True)
    args = parser.parse_args()
    if ROOT / "artifacts" not in args.output.resolve().parents:
        parser.error("Output must be inside artifacts/")
    account = json.loads(args.account.read_text(encoding="utf-8-sig"))
    # Match settings only within this explicitly disposable test job and restore them afterward.
    owner = {key: value for key, value in account.items() if key != "expectedModelSettings"}
    client, provider = authenticate(args.gateway, owner)
    model = next(item for item in provider["models"] if item["id"] == account["modelConfigId"])
    original = {key: model.get(key) for key in ("temperature", "topP", "maxOutputTokens", "timeoutMs", "supportsJson", "supportsTools", "responseFormat")}
    path = f"/ai-agent/model-providers/{account['providerId']}/models/{account['modelConfigId']}"
    with AccountLease(account["accountId"], "TASK_STATE"):
        try:
            before = client.call("GET", "/dashboards?limit=100&status=ALL")
            client.call("PUT", path, account.get("expectedModelSettings", original))
            authenticate(args.gateway, account)
            with ProviderObserver(client, provider) as observer:
                report = run_state_smoke(client, account, provider, observer, args.output)
            report["model"] = {key: account[key] for key in ("providerId", "modelConfigId", "modelId")}
            report["modelSettings"] = account.get("expectedModelSettings", original)
            report["harnessSha256"] = sha(ROOT / "scripts/ai_dashboard_eval/state_smoke.py")
            java = ROOT / "backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent"
            report["stateSourceHashes"] = {str(file.relative_to(ROOT)): sha(file) for file in
                [java / path for path in ("domain/DashboardTaskState.java", "domain/DashboardTurn.java",
                 "adapter/JdbcDashboardTasks.java", "service/DashboardTaskConversation.java", "service/DashboardTaskContext.java",
                 "service/CreationTaskPlanning.java", "service/AiAgentOrchestrator.java")]}
        finally:
            client.call("PUT", path, original)
        restored = client.call("GET", "/ai-agent/model-providers/" + account["providerId"])
        report["noDashboardMutation"] = before == client.call("GET", "/dashboards?limit=100&status=ALL")
        report["providerRestored"] = restored["baseUrl"].rstrip("/") == provider["baseUrl"].rstrip("/")
        restored_model = next(item for item in restored["models"] if item["id"] == account["modelConfigId"])
        report["modelSettingsRestored"] = all(restored_model.get(key) == value for key, value in original.items())
        if not report["providerRestored"] or not report["modelSettingsRestored"] or not report["noDashboardMutation"]:
            report["status"] = "FAILED"
        write_json(args.output, report)
        print(json.dumps({key: report[key] for key in ("status", "qualityStatus", "providerRestored", "modelSettingsRestored")}))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
