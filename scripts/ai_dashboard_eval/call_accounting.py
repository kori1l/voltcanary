"""A2 trace accounting. A logical first attempt can require planner, step and context calls."""

RECOVERY = {"FORMAT_CORRECTION", "DOMAIN_RECOVERY", "OUTCOME_RECOVERY"}
PRIMARY = {"PRIMARY", "CONTEXT_ACQUISITION"}
ASSESSMENT = {"OUTCOME_ASSESSMENT"}


def from_trace(events):
    starts, finishes = {}, {}
    for event in events:
        if event.get("stage") != "MODEL_CALL":
            continue
        details = event.get("details") or {}
        identity = details.get("callId") or event.get("attemptId")
        if not identity:
            return {"modelCalls": None, "repairCalls": None, "complete": False, "calls": []}
        if event.get("status") == "STARTED":
            starts[identity] = event
        else:
            finishes[identity] = event
    calls = []
    for identity, event in starts.items():
        details = event.get("details") or {}
        completed = finishes.get(identity, {})
        outcome = completed.get("details") or {}
        usage = outcome.get("usage")
        calls.append({"callId": identity, "phase": details.get("phase"), "purpose": details.get("purpose"),
                      "modelConfigId": details.get("modelConfigId"), "outputMode": details.get("outputMode"),
                      "status": completed.get("status", "UNKNOWN"), "code": completed.get("code"),
                      "latencyMs": completed.get("durationMs"), "usage": usage if isinstance(usage, dict) else None,
                      "reservedCostMicros": details.get("reservedCostMicros"),
                      "estimatedInputTokens": details.get("estimatedInputTokens"), "reservedOutputTokens": details.get("reservedOutputTokens")})
    complete = bool(calls) and all(call["purpose"] in PRIMARY | RECOVERY | ASSESSMENT and call["callId"] in finishes for call in calls)
    complete = complete and not any(event.get("truncated") for event in events if event.get("stage") == "MODEL_CALL")
    if not calls and not starts and not finishes:
        # A recorded pre-model rejection is a known zero, not missing provider evidence.
        rejected_before_model = any(e.get("stage") == "PREPARATION" and e.get("status") == "FAILED" for e in events)
        uncertain_network = any(e.get("stage") in {"MODEL_CALL", "MODEL_RUN", "PROVIDER_USAGE", "DIAGNOSTIC_READ"}
                                or e.get("stage") == "BUDGET" and e.get("status") == "RESERVED" for e in events)
        complete = rejected_before_model and not uncertain_network
    return {"modelCalls": len(calls), "repairCalls": sum(c["purpose"] in RECOVERY for c in calls) if complete else None,
            "assessmentCalls": sum(c["purpose"] in ASSESSMENT for c in calls) if complete else None,
            "complete": complete, "calls": calls}


def first_pass(passed, accounting):
    if not passed:
        return False
    if not accounting or not accounting.get("complete") or not accounting.get("modelCalls"):
        return None
    return accounting["repairCalls"] == 0


def quality_metrics(turns):
    total = len(turns)
    first = sum(t.get("firstTryPassed") is True for t in turns)
    recovered = sum(t.get("passed") and t.get("firstTryPassed") is False and (t.get("repairCalls") or 0) > 0 for t in turns)
    final = sum(bool(t.get("passed")) for t in turns)
    failures = total - first
    return {"firstPassSuccesses": first, "firstPassTrials": total, "firstPassSuccessRate": first / total if total else None,
            "firstPassUnknown": sum(t.get("passed") and t.get("firstTryPassed") is None for t in turns),
            "initialUnsuccessfulTrials": failures, "recoveredTrials": recovered,
            "recoveryRate": recovered / failures if failures else None,
            "finalSuccesses": final, "finalTrials": total, "finalSuccessRate": final / total if total else None}
