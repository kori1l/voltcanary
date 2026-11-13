"""Capped A5 checkpoint. Fixed corpus and independent predicates; profile and recovery are explicit."""
import argparse
import json
from pathlib import Path
import sys
import time
from datetime import datetime, timezone

from ai_dashboard_eval.account_lock import AccountLease
from ai_dashboard_eval.api import ApiError, ROOT, authenticate, fingerprint, sha, write_json
from ai_dashboard_eval.conversations import ConversationRunner
from ai_dashboard_eval.runner import summarize, validate_corpus

CASES = ("tram-original-name", "move-only-three", "heldout-compact-wording", "heldout-map-footer-wording")
LIMITS = {"calls": 40, "inputTokens": 7_000_000, "outputTokens": 720_000, "reservedCostMicros": 4_000_000, "seconds": 1200}
TURN_RESERVATION = {"calls": 6, "inputTokens": 700_000, "outputTokens": 72_000, "reservedCostMicros": 600_000}


def totals(diagnostics):
    calls = [call for turn in diagnostics for call in turn["callAccounting"]["calls"]]
    fields = {"inputTokens": "estimatedInputTokens", "outputTokens": "reservedOutputTokens", "reservedCostMicros": "reservedCostMicros"}
    return {"calls": len(calls), **{key: None if any(call.get(field) is None for call in calls) else sum(call[field] for call in calls)
                                  for key, field in fields.items()}}


class DiagnosticRunner(ConversationRunner):
    def __init__(self, *args, **kwargs):
        self.previous_usage = kwargs.pop("previous_usage", {key: 0 for key in TURN_RESERVATION})
        previous_elapsed = kwargs.pop("previous_elapsed", 0)
        self.expected_profile = kwargs.pop("expected_profile", "FIRST_PASS_DIAGNOSTIC")
        super().__init__(*args, **kwargs)
        self.started = time.monotonic() - previous_elapsed
        self.halted = None

    def model(self, message, plan, devices):
        self._turn_accounting.value = None
        spent = totals(self.diagnostics)
        spent = {key: None if value is None or self.previous_usage.get(key) is None else value + self.previous_usage[key]
                 for key, value in spent.items()}
        if self.halted or time.monotonic() - self.started + 240 > LIMITS["seconds"]:
            raise ApiError(0, self.halted or "CAMPAIGN_TIME_CEILING")
        for key, reserve in TURN_RESERVATION.items():
            if spent[key] is None or spent[key] + reserve > LIMITS[key]:
                self.halted = "CAMPAIGN_BUDGET_CEILING"
                raise ApiError(0, self.halted)
        profile = self.api.call("GET", "/ai-agent/orchestration/profile")
        diagnostic = self.expected_profile == "FIRST_PASS_DIAGNOSTIC"
        judge = self.expected_profile == "VERIFIED"
        if (not profile.get("enabled") or profile.get("profile") != self.expected_profile
                or profile.get("automaticCorrections") != (not diagnostic) or profile.get("modelJudge") != judge):
            self.halted = "DIAGNOSTIC_PROFILE_REQUIRED"
            raise ApiError(0, self.halted)
        result = super().model(message, plan, devices)
        accounting = result["_evaluationCallAccounting"]
        if (not accounting["complete"] or diagnostic and accounting["repairCalls"] != 0
                or not judge and accounting.get("assessmentCalls", 0) != 0):
            self.halted = "DIAGNOSTIC_ACCOUNTING_INCOMPLETE"
            raise ApiError(0, self.halted)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--previous-campaign", type=Path, help="Link immutable earlier outcomes and share their remaining aggregate ceiling")
    parser.add_argument("--profile", choices=("FIRST_PASS_DIAGNOSTIC", "STANDARD", "VERIFIED"), default="FIRST_PASS_DIAGNOSTIC")
    parser.add_argument("--include-design", action="store_true", help="Include the separately versioned open-ended/mixed design cases")
    parser.add_argument("--design-corpus-version", choices=("v1", "v2"), default="v2", help="v1 reproduces its documented default-period oracle defect; no report rescoring")
    parser.add_argument("--cases", nargs="+", help="Explicit predeclared subset; no change to any case predicates")
    args = parser.parse_args()
    output = args.output.resolve()
    if ROOT / "artifacts" not in output.parents or output.exists():
        parser.error("Use a NEW output directory under artifacts/; old outcomes are immutable")
    path = ROOT / "backend/services/ai-agent-service/src/test/resources/evaluation/dashboard-conversation-ru-v1.json"
    corpus = json.loads(path.read_text(encoding="utf-8-sig"))
    validate_corpus(corpus)
    selected = [case for case in corpus["cases"] if case["id"] in CASES]
    if len(selected) != len(CASES):
        parser.error("Frozen checkpoint cases are missing")
    if args.include_design:
        extension = ROOT / ("backend/services/ai-agent-service/src/test/resources/evaluation/dashboard-design-ru-" + args.design_corpus_version + ".json")
        extra = json.loads(extension.read_text(encoding="utf-8"))
        validate_corpus({**corpus, "cases": corpus["cases"] + extra["cases"]})
        selected = extra["cases"] + selected
    if args.cases:
        available = {case["id"]: case for case in selected}
        if len(set(args.cases)) != len(args.cases) or not set(args.cases) <= set(available):
            parser.error("Unknown/duplicate case selection")
        selected = [available[name] for name in args.cases]
    manifest = {"version": "a5-first-pass-checkpoint-v4", "profile": args.profile, "cases": [case["id"] for case in selected], "trialsPerCase": 1,
                "limits": LIMITS, "turnUpperBound": TURN_RESERVATION, "threshold": {"allFrozenPredicates": 1.0, "allDialogues": 1.0,
                "unauthorizedEffects": 0, "collateralChanges": 0}, "historicalLive12": {"passedTurns": 1, "turns": 5, "passedDialogues": 0, "dialogues": 2},
                "metadata": fingerprint(path), "recordedAt": datetime.now(timezone.utc).isoformat(), "job": "PLAN_ONLY"}
    manifest["entryPointSha256"] = sha(Path(__file__))
    if args.include_design:
        manifest["designCorpusSha256"] = sha(extension)
        manifest["designCorpusVersion"] = extra["version"]
    previous_usage = {key: 0 for key in TURN_RESERVATION}
    previous_elapsed = 0
    if args.previous_campaign:
        prior = json.loads(args.previous_campaign.read_text(encoding="utf-8-sig"))
        previous_usage = prior.get("cumulativeReservedUsage", prior["reservedUsage"])
        previous_elapsed = max(0, (datetime.now(timezone.utc) - datetime.fromisoformat(prior["recordedAt"])).total_seconds())
        manifest.update(previousCampaign=str(args.previous_campaign), previousQualityStatus=prior["qualityStatus"],
                        previousRecordedOutcomes={key: prior.get(key) for key in ("passedTurns", "turnCount", "firstPassSuccessRate", "passedTrials")},
                        versionedReevaluation=True, statisticallyIndependent=False, independentTrialCount=0, previousUsage=previous_usage)
    write_json(output / "predeclared.json", manifest)
    report = dict(manifest)
    try:
        account = json.loads(args.account.read_text(encoding="utf-8-sig"))
        client, provider = authenticate(args.gateway, account)
        model = next(model for model in provider["models"] if model["id"] == account["modelConfigId"])
        report["model"] = {key: model.get(key) for key in ("id", "modelId", "supportsJson", "supportsTools", "responseFormat", "maxOutputTokens", "timeoutMs", "temperature", "topP")}
        with AccountLease(account["accountId"], "A5_" + args.profile):
            before = client.call("GET", "/dashboards?limit=100&status=ALL")
            if before.get("hasNext"):
                raise ApiError(0, "BOUNDED_ACCOUNT_REQUIRED")
            runner = DiagnosticRunner(client, account, output, previous_usage=previous_usage, previous_elapsed=previous_elapsed, expected_profile=args.profile)
            for case in selected:
                runner.run_case(case, 1)
            after = client.call("GET", "/dashboards?limit=100&status=ALL")
        report.update(summarize(runner.results, len(selected)))
        report.update(trials=runner.results, diagnostics=runner.diagnostics, reservedUsage=totals(runner.diagnostics),
                      noDashboardMutation=before == after, historicalScoresReplaced=False, liveInvocationEvidence="A2_SERVER_TRACE",
                      costStatus="UNKNOWN_UNPRICED", qualified=False)
        report["cumulativeReservedUsage"] = {key: None if value is None or previous_usage.get(key) is None else value + previous_usage[key]
                                            for key, value in report["reservedUsage"].items()}
        write_json(output / "summary.json", report)
        print(json.dumps({key: report[key] for key in ("turnCount", "passedTurns", "passedTrials", "firstPassSuccessRate", "recoveryRate", "finalSuccessRate", "modelCalls", "qualityStatus", "noDashboardMutation")}))
        return 0 if report["qualityStatus"] == "PASSED" and report["noDashboardMutation"] else 1
    except ApiError as error:
        report.update(qualityStatus="BLOCKED", httpStatus=error.status, errorCode=error.code, qualified=False)
        write_json(output / "summary.json", report)
        print(json.dumps({"qualityStatus": "BLOCKED", "errorCode": error.code, "httpStatus": error.status}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
