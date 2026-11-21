"""Run one review-only specialist continuation on a configured real model."""
import argparse
from pathlib import Path
import uuid

from ai_dashboard_eval.account_lock import AccountLease
from ai_dashboard_eval.api import ApiError, ROOT, authenticate, sha, write_json
from ai_dashboard_eval.fixtures import VehicleFixture
from ai_dashboard_eval.grading import definition
import json


def run(api, account, report):
    chat = str(uuid.uuid4())
    path = f"/ai-agent/conversations/{chat}"
    fixture = VehicleFixture(api, "QA consultation " + chat[:8])

    def turn(text, step=None):
        detail = api.call("GET", path)
        request = str(uuid.uuid4())
        body = dict(requestId=request, expectedRevision=detail["conversation"]["revision"],
                    text=text, context={"deviceIds": fixture.devices},
                    providerId=account["providerId"], modelConfigId=account["modelConfigId"])
        if step is not None:
            body["continueStepId"] = step
        result = api.call("POST", path + "/messages", body)
        trace = api.call("GET", f"{path}/turns/{request}/trace/export")
        report["turns"].append(dict(requestId=request, planId=result["orchestration"]["planId"],
                                    status=result["plan"]["planningStatus"],
                                    modelEvents=[{"status": e.get("status"), "code": e.get("code")}
                                                 for e in trace if e.get("stage") == "MODEL_CALL"]))
        return result

    try:
        fixture.create()
        api.call("POST", "/ai-agent/conversations", {"conversationId": chat})
        initial = turn("Prepare a dashboard for the selected device showing current speed. "
                       "Ask me for the name before preparing a review.")
        plan = initial["orchestration"]["planId"]
        url = f"/ai-agent/plans/{plan}"
        snapshot = api.call("GET", url + "/requirements")
        question = snapshot.get("question")
        assert question, "TYPED_QUESTION_MISSING"
        before = api.call("GET", url + "/consultations")
        report["before"] = before
        assert len(before) == 1 and before[0]["status"] == "WAITING_USER", "SPECIALIST_NOT_WAITING"
        step = initial["orchestration"]["pendingQuestion"]["stepId"]
        name = "QA Velocity " + chat[:8]
        answer = dict(planId=plan, requestId=str(uuid.uuid4()), questionId=question["id"],
                      expectedQuestionRevision=question["revision"], expectedRequirementsRevision=snapshot["revision"],
                      answer=dict(kind="TEXT", text=name))
        accepted = api.call("POST", url + "/requirements/answers", answer)
        assert accepted["slots"][question["slots"][0]]["value"]["value"] == name, "ANSWER_NOT_PERSISTED"
        assert api.call("POST", url + "/requirements/answers", answer) == accepted, "ANSWER_REPLAY_CHANGED"
        prepared = turn("Continue preparing the same dashboard using the confirmed name and current speed.", step)
        after = api.call("GET", url + "/consultations")
        report["after"] = after
        assert prepared["orchestration"]["planId"] == plan, "PLAN_CHANGED"
        assert (definition(prepared["plan"]) or {}).get("name") == name, "REVIEW_NAME_MISMATCH"
        assert len(after) == 1 and after[0]["id"] == before[0]["id"] and after[0]["status"] == "SUCCEEDED" \
            and after[0]["exchanges"] == 2, "SPECIALIST_NOT_RESUMED"
        report["status"] = "PASSED"
    finally:
        report["cleanupFailures"] = fixture.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--account", type=Path, required=True, help="Account metadata, with authentication supplied via environment")
    parser.add_argument("--output", type=Path, required=True, help="Summary JSON within ignored artifacts")
    args = parser.parse_args()
    if ROOT / "artifacts" not in args.output.resolve().parents:
        parser.error("--output must be inside artifacts/")
    report = {"status": "FAILED", "turns": [], "harnessHash": sha(Path(__file__))}
    try:
        account = json.loads(args.account.read_text(encoding="utf-8-sig"))
        api, _ = authenticate(args.gateway, account)
        api.timeout = 240
        report["model"] = account["modelId"]
        report["profile"] = api.call("GET", "/ai-agent/orchestration/profile")
        assert report["profile"]["enabled"], "GRAPH_DISABLED"
        with AccountLease(account["accountId"], "PLANNING"):
            run(api, account, report)
    except ApiError as error:
        report["error"] = error.code
        report["httpStatus"] = error.status
    except AssertionError as error:
        report["error"] = str(error)
    finally:
        write_json(args.output, report)
        print(json.dumps(report))
    return int(report["status"] != "PASSED" or bool(report.get("cleanupFailures")))


if __name__ == "__main__":
    raise SystemExit(main())
