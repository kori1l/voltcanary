"""Stage-four scenario replay with complete durable conversations and safe traces."""
import argparse
from datetime import datetime, timezone
import json
import sys
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

from ai_dashboard_eval.account_lock import AccountLease
from ai_dashboard_eval.api import ApiError, ROOT, authenticate, fingerprint, write_json
from ai_dashboard_eval.conversations import ConversationRunner
from ai_dashboard_eval.runner import summarize, validate_corpus


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cases", required=True)
    parser.add_argument("--trials", type=int, default=1, choices=range(1, 6))
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--workers", type=int, choices=(1, 2), default=1)
    args = parser.parse_args()
    output = args.output.resolve()
    if ROOT / "artifacts" not in output.parents:
        parser.error("Output must be under artifacts/")
    path = ROOT / "backend/services/ai-agent-service/src/test/resources/evaluation/dashboard-conversation-ru-v1.json"
    corpus = json.loads(path.read_text(encoding="utf-8-sig"))
    validate_corpus(corpus)
    wanted = set(args.cases.split(","))
    cases = [case for case in corpus["cases"] if case["id"] in wanted]
    if {case["id"] for case in cases} != wanted:
        parser.error("Unknown scenario")
    if any(turn.get("fault") for case in cases for turn in case["turns"]):
        parser.error("Fault injection requires the original isolated-provider harness")
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "stage": 4,
              "job": "PLAN_ONLY", "corpusVersion": corpus["version"], "corpusRevision": corpus.get("revision", 1),
              "historicalScoresReplaced": False, "releaseStageFiveOrSixAcceptance": False,
              "metadata": fingerprint(path)}
    try:
        account = json.loads(args.account.read_text(encoding="utf-8-sig"))
        client, provider = authenticate(args.gateway, account)
        model = next(model for model in provider["models"] if model["id"] == account["modelConfigId"])
        report["model"] = {key: model.get(key) for key in ("id", "modelId", "maxOutputTokens", "timeoutMs", "temperature")}
        with AccountLease(account["accountId"], "STAGE_FOUR_HISTORY_REPLAY"):
            before = client.call("GET", "/dashboards?limit=100&status=ALL")
            runner = ConversationRunner(client, account, output)
            with ThreadPoolExecutor(max_workers=args.workers) as pool:
                pending = [pool.submit(runner.run_case, case, trial) for trial in range(1, args.trials + 1) for case in cases]
                for completed in as_completed(pending):
                    completed.result()
            after = client.call("GET", "/dashboards?limit=100&status=ALL")
        report.update(summarize(runner.results, len(cases) * args.trials))
        report.update(trials=runner.results, noDashboardMutation=before == after,
                      diagnostics=runner.diagnostics, liveInvocationEvidence="SERVER_PERSISTED_TRACE")
        report["failureAnalysis"] = failure_analysis(runner.results, runner.diagnostics)
        write_json(output / "summary.json", report)
        print(json.dumps({key: report[key] for key in ("trialCount", "passedTrials", "turnCount", "passedTurns", "qualityStatus", "noDashboardMutation")}), flush=True)
        return 0 if report["qualityStatus"] == "PASSED" and report["noDashboardMutation"] else 1
    except ApiError as error:
        report.update(qualityStatus="BLOCKED", httpStatus=error.status, errorCode=error.code)
        write_json(output / "summary.json", report)
        print(f"BLOCKED: {error.code}, HTTP {error.status}", flush=True)
        return 2


def failure_analysis(results, diagnostics):
    by_plan = {record["planId"]: record for record in diagnostics if record.get("planId")}
    analysis = []
    for result in results:
        attempts = [record for record in diagnostics if record.get("deviceIds") == (result.get("fixture") or {}).get("deviceIds")]
        message_index = 0
        if result.get("setupFailure"):
            analysis.append({"caseId": result["caseId"], "category": "FIXTURE_UNAVAILABLE", "evidence": result["setupFailure"]})
        for turn in result["turns"]:
            attempt = attempts[message_index] if turn.get("action") == "message" and message_index < len(attempts) else {}
            if turn.get("action") == "message":
                message_index += 1
            if turn["passed"]:
                continue
            diagnostic = by_plan.get(turn.get("planId"), attempt)
            failures = diagnostic.get("failures", [])
            analysis.append({"caseId": result["caseId"], "trial": result["trial"], "turnId": turn["id"],
                "category": "OBSERVED_PIPELINE_FAILURE" if failures else "OUTCOME_MISMATCH" if turn.get("category") == "MODEL_RESULT" else turn.get("category"),
                "failedPredicates": [check for check in turn.get("checks", []) if not check["passed"]],
                "conversationId": diagnostic.get("conversationId"), "requestId": diagnostic.get("requestId"),
                "traceFailures": failures, "providerEvidence": diagnostic.get("providerEvidence", []), "artifact": diagnostic.get("artifact")})
    return analysis


if __name__ == "__main__":
    sys.exit(main())
