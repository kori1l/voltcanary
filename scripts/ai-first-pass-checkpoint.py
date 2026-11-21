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
    if any(not turn["callAccounting"].get("complete") for turn in diagnostics):
        return {key: None for key in TURN_RESERVATION}
    calls = [entry for turn in diagnostics for entry in
             turn["callAccounting"]["calls"] + turn["callAccounting"].get("deniedReservations", [])]
    fields = {"inputTokens": "estimatedInputTokens", "outputTokens": "reservedOutputTokens", "reservedCostMicros": "reservedCostMicros"}
    return {"calls": len(calls), **{key: None if any(call.get(field) is None for call in calls) else sum(call[field] for call in calls)
                                  for key, field in fields.items()}}


def diagnostic_passed(results, cases):
    if not cases or len(results) != len(cases):
        return False
    by_case = {trial["caseId"]: trial for trial in results}
    for case in cases:
        trial = by_case.get(case["id"])
        if not trial or trial.get("passed") is not True:
            return False
        turns = trial["turns"]
        if [turn["id"] for turn in turns] != [turn["id"] for turn in case["turns"]]:
            return False
        for expected, observed in zip(case["turns"], turns):
            if observed.get("passed") is not True:
                return False
            if expected["action"] == "message" and observed.get("firstTryPassed") is not True:
                return False
    return any(turn["action"] == "message" for case in cases for turn in case["turns"])


def record_campaign(report, runner, selected, previous_usage):
    reserved = totals(runner.diagnostics)
    report.update(summarize(runner.results, len(selected)))
    report.update(trials=runner.results, diagnostics=runner.diagnostics, reservedUsage=reserved,
                  historicalScoresReplaced=False, liveInvocationEvidence="A2_SERVER_TRACE",
                  costStatus="UNKNOWN_UNPRICED", checkpointStopCode=runner.halted, qualified=False)
    report["cumulativeReservedUsage"] = {
        key: None if value is None or previous_usage.get(key) is None else value + previous_usage[key]
        for key, value in reserved.items()
    }


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
        if self.halted:
            raise ApiError(0, self.halted)
        if time.monotonic() - self.started + 240 > LIMITS["seconds"]:
            self.halted = "CAMPAIGN_TIME_CEILING"
            raise ApiError(0, self.halted)
        for key, reserve in TURN_RESERVATION.items():
            if spent[key] is None or spent[key] + reserve > LIMITS[key]:
                self.halted = "CAMPAIGN_BUDGET_CEILING"
                raise ApiError(0, self.halted)
        try:
            verify_profile(self.api, self.expected_profile)
        except ApiError as error:
            self.halted = error.code
            raise
        diagnostic = self.expected_profile == "FIRST_PASS_DIAGNOSTIC"
        judge = self.expected_profile == "VERIFIED"
        try:
            result = super().model(message, plan, devices)
        except ApiError:
            self.check_accounting(self._turn_accounting.value, diagnostic, judge)
            raise
        self.check_accounting(result["_evaluationCallAccounting"], diagnostic, judge)
        return result

    def check_accounting(self, accounting, diagnostic, judge):
        if (accounting is None or not accounting["complete"]
                or diagnostic and accounting["repairCalls"] != 0
                or not judge and accounting.get("assessmentCalls", 0) != 0):
            self.halted = "DIAGNOSTIC_ACCOUNTING_INCOMPLETE"
            raise ApiError(0, self.halted)
        if time.monotonic() - self.started > LIMITS["seconds"]:
            self.halted = "CAMPAIGN_TIME_CEILING"
            raise ApiError(0, self.halted)
        for key, value in totals(self.diagnostics).items():
            if value is None or value + self.previous_usage[key] > LIMITS[key]:
                self.halted = "CAMPAIGN_BUDGET_CEILING"
                raise ApiError(0, self.halted)


def verify_profile(api, expected):
    profile = api.call("GET", "/ai-agent/orchestration/profile")
    if (profile.get("enabled") is not True or profile.get("profile") != expected
            or profile.get("automaticCorrections") is not (expected != "FIRST_PASS_DIAGNOSTIC")
            or profile.get("modelJudge") is not (expected == "VERIFIED")):
        raise ApiError(0, "DIAGNOSTIC_PROFILE_REQUIRED")
    return {key: profile[key] for key in ("enabled", "profile", "automaticCorrections", "modelJudge")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", type=Path)
    parser.add_argument("--preflight-only", action="store_true",
                        help="Write the frozen cases and limits without authentication or model calls")
    parser.add_argument("--preflight-manifest", type=Path,
                        help="Require the same corpus, harness, profile, cases and limits as this offline preflight")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--previous-campaign", type=Path, help="Link immutable earlier outcomes and share their remaining aggregate ceiling")
    parser.add_argument("--profile", choices=("FIRST_PASS_DIAGNOSTIC", "STANDARD", "VERIFIED"), default="FIRST_PASS_DIAGNOSTIC")
    parser.add_argument("--include-design", action="store_true", help="Include the separately versioned open-ended/mixed design cases")
    parser.add_argument("--design-corpus-version", choices=("v1", "v2"), default="v2", help="v1 reproduces its documented default-period oracle defect; no report rescoring")
    parser.add_argument("--checkpoint-oracle", choices=("v1", "v2"), default="v2",
                        help="v1 retains the original preservation conflict for historical reproduction")
    parser.add_argument("--cases", nargs="+", help="Explicit predeclared subset; no change to any case predicates")
    args = parser.parse_args()
    if not args.preflight_only and args.account is None:
        parser.error("--account is required for a live checkpoint")
    if not args.preflight_only and args.profile == "FIRST_PASS_DIAGNOSTIC" and args.preflight_manifest is None:
        parser.error("--preflight-manifest is required for a live first-pass diagnostic")
    output = args.output.resolve()
    if ROOT / "artifacts" not in output.parents or output.exists():
        parser.error("Use a NEW output directory under artifacts/; old outcomes are immutable")
    path = ROOT / "backend/services/ai-agent-service/src/test/resources/evaluation/dashboard-conversation-ru-v1.json"
    corpus = json.loads(path.read_text(encoding="utf-8-sig"))
    validate_corpus(corpus)
    selected = [case for case in corpus["cases"] if case["id"] in CASES]
    if len(selected) != len(CASES):
        parser.error("Frozen checkpoint cases are missing")
    if args.checkpoint_oracle == "v2":
        original = next(case for case in selected if case["id"] == "tram-original-name")
        move = next(turn for turn in original["turns"] if turn["id"] == "move")
        move["expect"]["preserve"]["history:doors"] = ["/presentation/visualization"]
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
    manifest["checkpointOracle"] = args.checkpoint_oracle
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
    if args.preflight_manifest:
        try:
            previous = json.loads(args.preflight_manifest.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            parser.error("Preflight manifest is missing or invalid")
        if not isinstance(previous, dict) or {
                key: value for key, value in previous.items() if key != "recordedAt"
        } != {key: value for key, value in manifest.items() if key != "recordedAt"}:
            parser.error("Checkpoint differs from the offline preflight manifest")
    write_json(output / "predeclared.json", manifest)
    if args.preflight_only:
        print(json.dumps({"status": "PREFLIGHT_READY", "cases": manifest["cases"], "limits": LIMITS}))
        return 0
    report = dict(manifest)
    runner = None
    try:
        account = json.loads(args.account.read_text(encoding="utf-8-sig"))
        client, provider = authenticate(args.gateway, account)
        model = next(model for model in provider["models"] if model["id"] == account["modelConfigId"])
        report["model"] = {key: model.get(key) for key in ("id", "modelId", "supportsJson", "supportsTools", "responseFormat", "maxOutputTokens", "timeoutMs", "temperature", "topP")}
        with AccountLease(account["accountId"], "A5_" + args.profile):
            report["runtimeProfile"] = verify_profile(client, args.profile)
            before = client.call("GET", "/dashboards?limit=100&status=ALL")
            if before.get("hasNext"):
                raise ApiError(0, "BOUNDED_ACCOUNT_REQUIRED")
            runner = DiagnosticRunner(client, account, output, previous_usage=previous_usage, previous_elapsed=previous_elapsed, expected_profile=args.profile)
            for case in selected:
                runner.run_case(case, 1)
            after = client.call("GET", "/dashboards?limit=100&status=ALL")
        record_campaign(report, runner, selected, previous_usage)
        inventory_verified = not after.get("hasNext") and before == after
        if args.profile == "FIRST_PASS_DIAGNOSTIC" and not diagnostic_passed(runner.results, selected):
            report["qualityStatus"] = "FAILED"
        report["noDashboardMutation"] = inventory_verified
        write_json(output / "summary.json", report)
        print(json.dumps({key: report[key] for key in ("turnCount", "passedTurns", "passedTrials", "firstPassSuccessRate", "recoveryRate", "finalSuccessRate", "modelCalls", "qualityStatus", "noDashboardMutation")}))
        return 0 if report["qualityStatus"] == "PASSED" and inventory_verified and not runner.halted else 1
    except ApiError as error:
        if runner is not None:
            record_campaign(report, runner, selected, previous_usage)
            report["noDashboardMutation"] = None
        report.update(qualityStatus="BLOCKED", httpStatus=error.status, errorCode=error.code, qualified=False)
        write_json(output / "summary.json", report)
        print(json.dumps({"qualityStatus": "BLOCKED", "errorCode": error.code, "httpStatus": error.status}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
