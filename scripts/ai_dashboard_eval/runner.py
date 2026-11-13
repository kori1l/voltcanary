"""Real-path multi-turn runner. Default mode prepares only; failures stay failures."""
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import Counter
from copy import deepcopy
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid

from .api import ApiError, Client, write_json
from .fixtures import VehicleFixture
from .grading import classify, definition, grade
from .call_accounting import first_pass, quality_metrics


def expand(value, variables):
    if isinstance(value, str):
        for key, item in variables.items():
            value = value.replace("{{" + key + "}}", item)
        return value
    if isinstance(value, dict):
        return {k: expand(v, variables) for k, v in value.items()}
    if isinstance(value, list):
        return [expand(v, variables) for v in value]
    return value


def validate_corpus(corpus):
    if corpus.get("version") != "dashboard-conversation-ru-v1":
        raise ValueError("Unsupported corpus version")
    cases = corpus.get("cases", [])
    ids = [c["id"] for c in cases]
    if len(ids) < 24 or len(set(ids)) != len(ids) or sum(c["critical"] for c in cases) != 8:
        raise ValueError("Corpus requires >=24 unique scenarios and exactly8 critical sequences")
    for case in cases:
        if not re.fullmatch(r"[a-z][a-z0-9-]+", case["id"]) or type(case["critical"]) is not bool:
            raise ValueError("Invalid case identity/critical flag")
        if case["split"] not in ("development", "held-out") or not case.get("turns"):
            raise ValueError("Missing split/turns")
        if case["profile"] not in ("normal", "stale", "empty", "ambiguous", "pair"):
            raise ValueError("Unknown fixture profile")
        if case.get("mutationPolicy") != "PLAN_ONLY" or case.get("cleanup") != "DECOMMISSION_OWN_FIXTURE":
            raise ValueError("Corpus cannot authorize domain execution")
        seen = set()
        for turn in case["turns"]:
            if turn["id"] in seen:
                raise ValueError("Duplicate turn identity")
            if turn["action"] not in ("message", "recover", "select", "revoke", "concurrent", "foreign"):
                raise ValueError("Unknown turn action")
            if turn["action"] == "message" and not turn.get("message"):
                raise ValueError("Missing actual user message")
            if turn.get("fault") not in (None, "PREPARATION_TYPE_MISMATCH"):
                raise ValueError("Unknown fault")
            for key in ("expectedHttp", "allowedHttp"):
                if key in turn and (not isinstance(turn[key], list) or not turn[key] or
                                    any(type(code) is not int or not 400 <= code <= 599 for code in turn[key])):
                    raise ValueError("HTTP rejection predicates must be explicit error codes")
            if turn["action"] == "select" and turn["target"] not in seen:
                raise ValueError("History target must be an earlier turn")
            seen.add(turn["id"])
            expectations = [turn.get("expect", {})] + [r.get("expect", {}) for r in turn.get("requests", [])]
            for expected in expectations:
                validate_expectation(expected)


def validate_expectation(expected):
    fields = {"status", "name", "roles", "absent", "titles", "presentation", "views", "top",
              "heightMax", "period", "preserve", "allowNewRoles", "allowRoot", "allowReflow",
              "unchanged", "missingField", "disclosureAny", "onlyRoles", "historyPeriods"}
    if not isinstance(expected, dict) or not set(expected) <= fields:
        raise ValueError("Unknown outcome predicate; refusing silent omission")
    for key in ("roles", "absent", "top", "allowNewRoles", "allowRoot", "disclosureAny", "onlyRoles"):
        if key in expected and (not isinstance(expected[key], list) or
                                not all(isinstance(v, str) for v in expected[key])):
            raise ValueError("Outcome selector lists must contain strings")
    for key in ("titles", "presentation", "views", "heightMax", "preserve", "historyPeriods"):
        if key in expected and not isinstance(expected[key], dict):
            raise ValueError("Outcome mappings must be objects")
    for paths in expected.get("preserve", {}).values():
        if not isinstance(paths, list) or not all(isinstance(p, str) and p.startswith("/") for p in paths):
            raise ValueError("Preservation paths must be explicit rooted paths")
    status = expected.get("status", "READY")
    choices = status if isinstance(status, list) else [status]
    if not choices or any(s not in ("READY", "NEEDS_INPUT") for s in choices):
        raise ValueError("Unsupported expected planning status")


class Runner:
    def __init__(self, client, account, output, observer=None, run_id=None):
        self.api, self.account, self.output, self.observer = client, account, Path(output), observer
        self.run_id = run_id or uuid.uuid4().hex[:8]
        self.lock = threading.Lock()
        self.results = []
        self.foreign_api = None

    def model(self, message, plan, devices):
        if plan is None:
            return self.api.call("POST", "/ai-agent/plans", {"message": message,
                "context": {"deviceIds": devices}, "providerId": self.account["providerId"],
                "modelConfigId": self.account["modelConfigId"]})
        return self.api.call("POST", f"/ai-agent/plans/{plan['planId']}/clarifications", {"message": message})

    def turn_accounting(self, plan, turn):
        return plan.get("_evaluationCallAccounting") if plan and turn["action"] == "message" else None

    def recover(self, plan):
        return self.api.call("GET", "/ai-agent/plans/" + plan["planId"])

    def branch(self, message, plan, devices):
        return self.model(message, plan, devices)

    def run_case(self, case, trial):
        marker = f"EVAL_{self.run_id}_{case['id'].replace('-', '_')}_t{trial}"
        result = {"caseId": case["id"], "trial": trial, "critical": case["critical"],
                  "split": case["split"], "turns": [], "fixture": None, "cleanup": None}
        fixture = VehicleFixture(self.api, marker, case["profile"])
        try:
            with fixture:
                result["fixture"] = fixture.metadata()
                variables = {"name": "QA " + case["id"], "vehicle": marker,
                             "vehicleB": marker + "_B"}
                plan, saved, known_name = None, {}, False
                for raw_turn in case["turns"]:
                    if plan:
                        plan.pop("_evaluationCallAccounting", None)
                    turn = expand(raw_turn, variables)
                    text = turn.get("message", "")
                    expected_name = turn.get("expect", {}).get("name", variables["name"])
                    known_name = known_name or bool(expected_name and expected_name in text)
                    if turn.get("fault") and not self.observer:
                        result["turns"].append({"id": turn["id"], "category": "HARNESS_BLOCKED",
                            "passed": False, "checks": [{"id": "fault_observer_required", "passed": False}]})
                        continue
                    previous = deepcopy(definition(plan)) if plan else None
                    start = len(self.observer.records) if self.observer else 0
                    record = {"id": turn["id"], "action": turn["action"], "checks": [],
                              "modelCalls": None, "repairCalls": None, "usage": None}
                    began = time.monotonic()
                    try:
                        if turn["action"] == "revoke":
                            fixture.revoke()
                            record["checks"].append({"id": "fixture_decommissioned", "passed": True})
                        elif turn["action"] == "foreign":
                            foreign_token = os.environ.get("VOLT_EVAL_FOREIGN_ACCESS_TOKEN")
                            if not foreign_token:
                                raise ApiError(0, "FOREIGN_AUTH_ENV_REQUIRED")
                            with self.lock:
                                if self.foreign_api is None:
                                    self.foreign_api = Client(self.api.gateway, foreign_token,
                                        refresh_token=os.environ.get("VOLT_EVAL_FOREIGN_REFRESH_TOKEN"))
                                    self.foreign_api.call("GET", "/accounts/current")
                            scope = self.foreign_api.call("GET", "/accounts/current/scope")
                            if str(scope.get("id", scope.get("accountId"))) == self.account["accountId"]:
                                raise ApiError(0, "FOREIGN_ACCOUNT_REQUIRED")
                            self.foreign_api.call("POST", f"/ai-agent/plans/{plan['planId']}/clarifications",
                                                  {"message": turn["message"]})
                            record["checks"].append({"id": "foreign_task_hidden", "passed": False})
                        elif turn["action"] == "select":
                            target = saved.get(turn["target"])
                            if not target:
                                raise ApiError(0, "HISTORY_TARGET_NOT_REACHED")
                            visible = target["planId"] in plan.get("previousReviewIds", [])
                            record["checks"].append({"id": "authorized_history_listed", "passed": visible})
                            plan = self.api.call("GET", "/ai-agent/plans/" + target["planId"])
                            record["checks"] += grade(plan, turn.get("expect", {}), exact=definition(target))
                        elif turn["action"] == "recover":
                            if not plan:
                                raise ApiError(0, "NO_REVIEW_TO_RECOVER")
                            plan = self.recover(plan)
                            record["checks"] += grade(plan, turn.get("expect", {}), exact=previous)
                        elif turn["action"] == "concurrent":
                            parent = plan
                            with ThreadPoolExecutor(max_workers=2) as pool:
                                futures = [pool.submit(self.branch, item["message"], parent, fixture.devices)
                                           for item in turn["requests"]]
                                responses = [f.result() for f in futures]
                            record["branchPlanIds"] = [p["planId"] for p in responses]
                            for index, (response, request) in enumerate(zip(responses, turn["requests"])):
                                for check in grade(response, request["expect"], previous, device_ids=fixture.devices):
                                    record["checks"].append({**check, "id": f"branch{index}:" + check["id"]})
                            plan = responses[0]
                        else:
                            if turn.get("fault"):
                                self.observer.arm(marker)
                            if case["profile"] not in ("stale", "empty") and not fixture.archived:
                                fixture.refresh()
                            plan = self.model(turn["message"], plan, fixture.devices)
                            record["category"] = classify(plan)
                            expected = turn.get("expect", {})
                            record["checks"] += grade(plan, expected, previous, device_ids=fixture.devices)
                            record["checks"].append({"id": "known_name_not_reasked", "passed":
                                not known_name or "name" not in (plan.get("clarification") or {}).get("missingFields", [])})
                            record["checks"].append({"id": "pinned_model", "passed":
                                plan.get("modelId") == self.account["modelId"] and
                                plan.get("modelConfigId") == self.account["modelConfigId"] and
                                not plan.get("fallbackUsed")})
                            record["checks"].append({"id": "provider_not_failed",
                                                     "passed": record["category"] == "MODEL_RESULT"})
                            if turn.get("expectedHttp"):
                                record["checks"].append({"id": "expected_request_rejection", "passed": False})
                        if plan:
                            saved[turn["id"]] = deepcopy(plan)
                            record.update(planId=plan["planId"], planningStatus=plan.get("planningStatus"))
                            # Fixture-only definitions, no unrestricted context or provider response.
                            write_json(self.output / marker / (turn["id"] + ".json"), {
                                "planId": plan["planId"], "payloadDigest": plan.get("payloadDigest"),
                                "definition": definition(plan), "previousReviewIds": plan.get("previousReviewIds", []),
                                "task": plan.get("task"),
                                "planningProtocols": [a.get("parameters", {}).get("planningProtocol", "legacy") for a in plan.get("actions", [])],
                                "operationChanges": [a.get("parameters", {}).get("operationChanges", {}) for a in plan.get("actions", [])],
                                "missingFields": (plan.get("clarification") or {}).get("missingFields", [])})
                        record.setdefault("category", "MODEL_RESULT" if turn["action"] == "message" else "CONTROL")
                    except ApiError as error:
                        record.update(category=classify(None, error), httpStatus=error.status, errorCode=error.code)
                        record["checks"].append({"id": "expected_request_rejection",
                            "passed": error.status in turn.get("expectedHttp", []) + turn.get("allowedHttp", [])})
                    record["latencyMs"] = round((time.monotonic() - began) * 1000)
                    if self.observer:
                        calls = self.observer.records_for(marker, start)
                        record["modelCalls"] = len(calls)
                        record["repairCalls"] = max(0, len(calls) - (2 if turn["action"] == "concurrent" else 1))
                        record["usage"] = [c["usage"] for c in calls]
                        allowed_rejection = record.get("httpStatus") in turn.get("allowedHttp", [])
                        if turn["action"] in ("message", "concurrent") and not turn.get("expectedHttp") and not allowed_rejection:
                            record["checks"].append({"id": "provider_invocation_observed", "passed": bool(calls)})
                        if turn.get("fault"):
                            record["checks"].append({"id": "preparation_fault_injected",
                                                     "passed": any(c["faultInjected"] for c in calls)})
                    record["passed"] = bool(record["checks"]) and all(c["passed"] for c in record["checks"])
                    accounting = self.turn_accounting(plan, turn)
                    if accounting:
                        record.update(modelCalls=accounting["modelCalls"], repairCalls=accounting["repairCalls"],
                                      callAccounting=accounting, usage=[call["usage"] for call in accounting["calls"]])
                    record["firstTryPassed"] = first_pass(record["passed"], accounting)
                    result["turns"].append(record)
                    print(f"{case['id']} trial{trial} {turn['id']}: {'PASS' if record['passed'] else 'FAIL'}", flush=True)
        except ApiError as error:
            result["setupFailure"] = {"category": "FIXTURE_UNAVAILABLE", "httpStatus": error.status, "code": error.code}
        result["cleanup"] = {"completed": not getattr(fixture, "cleanup_failures", []),
                             "failures": getattr(fixture, "cleanup_failures", [])}
        # Unreached steps remain explicitly failed in the denominator.
        reached = {t["id"] for t in result["turns"]}
        result["turns"] += [{"id": t["id"], "category": "NOT_REACHED", "passed": False, "checks": []}
                            for t in case["turns"] if t["id"] not in reached]
        result["passed"] = all(t["passed"] for t in result["turns"]) and result["cleanup"]["completed"]
        result["firstFailure"] = next((t["id"] for t in result["turns"] if not t["passed"]), None)
        with self.lock:
            self.results.append(result)
            write_json(self.output / "trials.json", self.results)
        return result


def summarize(results, expected_trials):
    turns = [t for r in results for t in r["turns"]]
    assertions = [c for t in turns for c in t.get("checks", [])]
    by_case = {}
    for result in results:
        by_case.setdefault(result["caseId"], []).append(result)
    return {"trialCount": len(results), "expectedTrialCount": expected_trials,
            "conversationCount": len(by_case), "passedTrials": sum(r["passed"] for r in results),
            "allTrialsPassedConversations": sum(all(r["passed"] for r in group) for group in by_case.values()),
            "turnCount": len(turns), "passedTurns": sum(t["passed"] for t in turns),
            "assertionCount": len(assertions), "passedAssertions": sum(c["passed"] for c in assertions),
            "firstTryPassedTurns": sum(t.get("firstTryPassed") is True for t in turns),
            "unavailableTurns": sum(t["category"] in ("AVAILABILITY_FAILURE", "PROVIDER_FAILURE", "HARNESS_BLOCKED", "NOT_REACHED") for t in turns),
            "modelCalls": None if any(t.get("modelCalls") is None for t in turns) else sum(t["modelCalls"] for t in turns),
            "repairCalls": None if any(t.get("repairCalls") is None for t in turns) else sum(t["repairCalls"] for t in turns),
            "failedChecks": dict(Counter(c["id"] for c in assertions if not c["passed"])),
            "turnCategories": dict(Counter(t["category"] for t in turns)),
            "cost": None,  # No unverified pricing or zero-cost inference from missing usage.
            "qualityStatus": "PASSED" if len(results) == expected_trials and all(r["passed"] for r in results) else "FAILED",
            "finalReleaseGate": "NOT_EVALUATED_BASELINE_ONLY", **quality_metrics(turns)}
