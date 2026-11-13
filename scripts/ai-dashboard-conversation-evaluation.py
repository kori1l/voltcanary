"""CLI entry point; use --help. All live output belongs in ignored artifacts/."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import nullcontext
import json
import re
from pathlib import Path
import sys
from datetime import datetime, timezone

from ai_dashboard_eval.api import ApiError, ROOT, authenticate, fingerprint, write_json
from ai_dashboard_eval.account_lock import AccountLease
from ai_dashboard_eval.observer import ProviderObserver
from ai_dashboard_eval.runner import Runner, summarize, validate_corpus


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--account", required=True, type=Path, help="Ignored test-account metadata; auth comes from environment")
    parser.add_argument("--corpus", type=Path, default=ROOT / "backend/services/ai-agent-service/src/test/resources/evaluation/dashboard-conversation-ru-v1.json")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--trials", type=int, default=2)
    parser.add_argument("--workers", type=int, choices=(1, 2), default=1)
    parser.add_argument("--cases", help="Comma-separated exact scenario IDs; omitted runs all")
    parser.add_argument("--observe-disposable-provider", action="store_true", help="Temporarily route a dedicated OPENAI test provider through a local metadata-only observer; restores its URL")
    parser.add_argument("--observer-port", type=int, default=18779)
    args = parser.parse_args()
    if not 1 <= args.trials <= 10:
        parser.error("trials must be1-10")
    output = args.output.resolve()
    if ROOT / "artifacts" not in output.parents:
        parser.error("output must be inside repository artifacts/")
    corpus = json.loads(args.corpus.read_text(encoding="utf-8-sig"))
    validate_corpus(corpus)
    cases = corpus["cases"]
    if args.cases:
        wanted = set(args.cases.split(","))
        cases = [c for c in cases if c["id"] in wanted]
        if {c["id"] for c in cases} != wanted:
            parser.error("Unknown scenario selection")
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "corpusVersion": corpus["version"],
              "job": "PLAN_ONLY", "requestedTrials": args.trials, "metadata": fingerprint(args.corpus)}
    try:
        account = json.loads(args.account.read_text(encoding="utf-8-sig"))
        client, provider = authenticate(args.gateway, account)
        report["model"] = {k: account[k] for k in ("providerId", "modelConfigId", "modelId")}
        selected_model = next(m for m in provider["models"] if m["id"] == account["modelConfigId"])
        report["modelSettings"] = {k: selected_model.get(k) for k in
            ("temperature", "topP", "maxOutputTokens", "timeoutMs", "supportsJson", "responseFormat")}
        report["corpusPromptVersion"] = corpus["promptVersion"]
        planner_source = (ROOT / "backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/service/ActionPlanner.java").read_text(encoding="utf-8")
        prompt = re.search(r"Planner prompt version: ([A-Za-z0-9-]+)", planner_source)
        report["promptVersion"] = prompt.group(1) if prompt else "UNKNOWN"
        report["promptVersionEvidence"] = "WORKSPACE_SOURCE; deployment parity requires separate verification"
        try:
            report["assistantRuntime"] = client.call("GET", "/ai-agent/orchestrations/runtime")
        except ApiError as error:
            if error.status != 404:
                raise
            report["assistantRuntime"] = {"generation": "legacy", "evidence": "V2_RUNTIME_ENDPOINT_ABSENT"}
        if report["assistantRuntime"].get("generation") == "assistant-v2":
            report["legacyPromptVersion"] = report["promptVersion"]
            report["promptVersion"] = "assistant-v2-primary-1"
            report["promptVersionEvidence"] = "AUTHENTICATED_RUNTIME_GENERATION; exact source parity requires separate verification"
        report["corpusRevision"] = corpus.get("revision", 1)
        report["modelSchemaVersion"] = corpus["modelSchemaVersion"]
        if report["assistantRuntime"].get("generation") == "assistant-v2":
            report["corpusModelSchemaVersion"] = report["modelSchemaVersion"]
            report["modelSchemaVersion"] = "assistant-v2"
            report["dashboardCommandProtocol"] = "dashboard-operations-v1"
        observer = ProviderObserver(client, provider, args.observer_port) if args.observe_disposable_provider else None
        with AccountLease(account["accountId"], "PLANNING"), observer if observer else nullcontext():
            before = client.call("GET", "/dashboards?limit=100&status=ALL")
            if before.get("hasNext"):
                raise ApiError(0, "USE_BOUNDED_TEST_ACCOUNT")
            runner = Runner(client, account, output, observer)
            with ThreadPoolExecutor(max_workers=args.workers) as pool:
                futures = [pool.submit(runner.run_case, case, trial)
                           for trial in range(1, args.trials + 1) for case in cases]
                for future in as_completed(futures):
                    future.result()
            results = runner.results
            after = client.call("GET", "/dashboards?limit=100&status=ALL")
        report.update(summarize(results, len(cases) * args.trials))
        report["noDashboardMutation"] = before == after
        report["dashboardInventoryBefore"] = inventory(before)
        report["dashboardInventoryAfter"] = inventory(after)
        report["observerRestored"] = not observer or client.call("GET", "/ai-agent/model-providers/" + account["providerId"])["baseUrl"].rstrip("/") == observer.original
        report["trials"] = results
        report["providerObservations"] = observer.records if observer else None
        report["liveInvocationEvidence"] = "OBSERVED" if observer and any(
            call.get("status") == 200 for call in observer.records) else "UNVERIFIED"
        report["baselineRecorded"] = True
        report["fullCorpus"] = len(cases) == len(corpus["cases"])
        write_json(output / "summary.json", report)
        print(json.dumps({k: report[k] for k in ("trialCount", "passedTrials", "turnCount", "passedTurns", "qualityStatus", "noDashboardMutation")}), flush=True)
        return 0 if report["qualityStatus"] == "PASSED" and report["noDashboardMutation"] else 1
    except ApiError as error:
        report.update(baselineRecorded=False, qualityStatus="BLOCKED", errorCode=error.code, httpStatus=error.status)
        write_json(output / "summary.json", report)
        print(f"BLOCKED: {error.code}, HTTP {error.status}", flush=True)
        return 2


def inventory(response):
    return [{key: item.get(key) for key in ("id", "status", "publishedRevision", "updatedAt")}
            for item in response.get("items", [])]


if __name__ == "__main__":
    sys.exit(main())
