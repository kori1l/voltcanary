"""Ordinary API evidence for revisioned task recovery, with a real model and explicit faults."""
import os
import subprocess
import uuid

from .api import ApiError, write_json
from .fixtures import VehicleFixture
from .grading import definition, grade


def count_model_sequences(account):
    # Read only allowlisted metadata; do not select run messages or provider credentials.
    actor = str(uuid.UUID(account["userId"]))
    provider = str(uuid.UUID(account["providerId"]))
    model = str(uuid.UUID(account["modelConfigId"]))
    sql = f"select count(*) from ai_agent_model_runs where user_id='{actor}' and provider_id='{provider}' and model_config_id='{model}'"
    result = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "sh", "-c",
                             'psql -U "$POSTGRES_USER" -d voltcanary_ai_agent -tAc "$1"', "eval-count", sql],
                            capture_output=True, text=True, check=True)
    return int(result.stdout.strip())


def run_state_smoke(client, account, provider, observer, output):
    marker = "EVAL_" + uuid.uuid4().hex[:8] + "_task_state_t1"
    report = {"job": "DASHBOARD_TASK_STATE", "marker": marker, "checks": [], "qualityChecks": [], "cleanup": None}
    checks = report["checks"]

    def check(name, value):
        checks.append({"id": name, "passed": bool(value)})

    def rejected(method, path, body, expected, token=None):
        try:
            client.call(method, path, body, token=token)
        except ApiError as error:
            return error.status == expected
        return False

    fixture = VehicleFixture(client, marker, "normal")
    try:
        with fixture:
            report["fixture"] = fixture.metadata()
            task = str(uuid.uuid4())
            name = "QA state " + marker
            body = {"message": f"Создай дашборд «{name}» для выбранного транспорта. Только одна карточка текущей скорости.",
                    "context": {"name": name, "deviceIds": fixture.devices}, "providerId": account["providerId"],
                    "modelConfigId": account["modelConfigId"], "requestId": str(uuid.uuid4()),
                    "taskId": task, "branchId": task, "expectedTaskRevision": 0}
            plan = client.call("POST", "/ai-agent/plans", body)
            report.update(taskId=task, reviewId=plan["planId"])
            check("name_in_independent_state", plan.get("task", {}).get("name") == name)
            report["qualityChecks"] = grade(plan, {"name": name, "roles": ["current:speed"]}, device_ids=fixture.devices)
            before = len(observer.records)
            replay = client.call("POST", "/ai-agent/plans", body)
            check("lost_response_exact_replay", replay == plan)
            check("replay_no_extra_provider_calls", len(observer.records) == before)
            changed = {**body, "message": body["message"] + " Добавь карту."}
            check("different_payload_conflicts", rejected("POST", "/ai-agent/plans", changed, 409))
            foreign = os.environ.get("VOLT_EVAL_FOREIGN_ACCESS_TOKEN")
            if not foreign:
                raise ApiError(0, "FOREIGN_AUTH_ENV_REQUIRED")
            foreign_scope = client.call("GET", "/accounts/current/scope", token=foreign)
            check("foreign_scope_is_different", str(foreign_scope.get("id", foreign_scope.get("accountId"))) != account["accountId"])
            check("foreign_task_hidden", rejected("GET", "/ai-agent/dashboard-tasks/" + task, None, 404, foreign))
            old = client.call("GET", "/ai-agent/dashboard-tasks/" + task)
            report["beforeFailure"] = old
            # Deliberately break only this disposable provider, after observing a real request.
            client.call("PUT", "/ai-agent/model-providers/" + provider["id"], {"baseUrl": "http://127.0.0.1:9/v1"})
            failure_body = {"message": "Назови Мой любимый трамвай", "requestId": str(uuid.uuid4()),
                            "taskId": task, "branchId": task, "expectedTaskRevision": plan["task"]["revision"],
                            "baseReviewId": plan["planId"]}
            path = f"/ai-agent/plans/{plan['planId']}/clarifications"
            before_failure = count_model_sequences(account)
            check("provider_fault_is_not_a_review", rejected("POST", path, failure_body, 502))
            recovered = client.call("GET", "/ai-agent/dashboard-tasks/" + task)
            report["afterFailure"] = recovered
            check("explicit_name_survives_failure", recovered["task"]["name"] == "Мой любимый трамвай")
            check("original_request_survives_failure", recovered["task"]["originalRequest"] == body["message"])
            check("last_valid_review_preserved", recovered["task"]["lastAcceptedReviewId"] == old["task"]["lastAcceptedReviewId"])
            check("pending_change_retained", any(item["request"] == failure_body["message"] for item in recovered["task"]["pending"]))
            if old["lastReview"] is not None:
                check("last_preview_definition_exact", definition(recovered["lastReview"]) == definition(old["lastReview"]))
            else:
                check("last_preview_definition_exact", False)
            sequences = count_model_sequences(account)
            check("provider_fault_was_recorded", sequences > before_failure)
            check("failed_turn_replay_same_error", rejected("POST", path, failure_body, 502))
            after_replay = count_model_sequences(account)
            report["failureModelSequences"] = {"before": before_failure, "afterFailure": sequences, "afterReplay": after_replay}
            check("failed_replay_no_extra_model_sequence", after_replay == sequences)
            stale = {**failure_body, "requestId": str(uuid.uuid4())}
            check("stale_revision_conflicts", rejected("POST", path, stale, 409))
            bad_task = str(uuid.uuid4())
            invalid = {**body, "taskId": bad_task, "branchId": bad_task, "requestId": str(uuid.uuid4()),
                       "context": {"name": name, "deviceIds": [str(uuid.uuid4())]}}
            try:
                client.call("POST", "/ai-agent/plans", invalid)
            except ApiError:
                pass
            initial_failure = client.call("GET", "/ai-agent/dashboard-tasks/" + bad_task)
            report["initialTaskId"] = bad_task
            check("initial_failure_recovery_without_review", initial_failure["lastReview"] is None
                  and initial_failure["task"]["name"] == name and initial_failure["task"]["status"] == "FAILED")
            check("unavailable_selection_is_not_permission", initial_failure["task"]["selectionStatus"] == "UNAVAILABLE")
            report["initialFailure"] = initial_failure
            # Reproduce the original failure before the first valid preview exists.
            client.call("PUT", "/ai-agent/model-providers/" + provider["id"],
                        {"baseUrl": f"http://host.docker.internal:{observer.port}/v1"})
            question_task = str(uuid.uuid4())
            question_body = {**body, "message": "Создай дашборд выбранного транспорта: только карточка текущей скорости.",
                             "context": {"deviceIds": fixture.devices}, "taskId": question_task, "branchId": question_task,
                             "requestId": str(uuid.uuid4())}
            question = client.call("POST", "/ai-agent/plans", question_body)
            check("initial_name_question_has_no_valid_review", question["task"]["lastAcceptedReviewId"] is None)
            client.call("PUT", "/ai-agent/model-providers/" + provider["id"], {"baseUrl": "http://127.0.0.1:9/v1"})
            question_path = f"/ai-agent/plans/{question['planId']}/clarifications"
            answer = {"message": "Назови Мой любимый трамвай", "requestId": str(uuid.uuid4()),
                      "taskId": question_task, "branchId": question_task,
                      "expectedTaskRevision": question["task"]["revision"], "baseReviewId": question["planId"]}
            check("name_reply_failure_before_first_preview", rejected("POST", question_path, answer, 502))
            failed_question = client.call("GET", "/ai-agent/dashboard-tasks/" + question_task)
            check("name_reply_checkpoint_before_first_preview", failed_question["lastReview"] is None
                  and failed_question["task"]["name"] == "Мой любимый трамвай")
            client.call("PUT", "/ai-agent/model-providers/" + provider["id"],
                        {"baseUrl": f"http://host.docker.internal:{observer.port}/v1"})
            continuation = {**answer, "message": "Продолжай с сохранённым именем.", "requestId": str(uuid.uuid4()),
                            "expectedTaskRevision": failed_question["task"]["revision"]}
            resumed = client.call("POST", question_path, continuation)
            check("failed_question_continues_current_branch", resumed["task"]["branchId"] == question_task)
            check("failed_question_advances_current_revision", resumed["task"]["revision"] == failed_question["task"]["revision"] + 1)
            check("failed_question_retains_name", resumed["task"]["name"] == "Мой любимый трамвай")
            report["questionRecovery"] = {"taskId": question_task, "before": failed_question, "after": resumed}
            report["questionQualityChecks"] = grade(resumed, {"name": "Мой любимый трамвай", "roles": ["current:speed"]}, device_ids=fixture.devices)
            write_json(output, report)
    except ApiError as error:
        report.update(errorCode=error.code, httpStatus=error.status)
        check("required_runtime_path_available", False)
    finally:
        report["cleanup"] = {"completed": not getattr(fixture, "cleanup_failures", []),
                             "failures": getattr(fixture, "cleanup_failures", [])}
    report["providerObservations"] = observer.records
    check("fixture_cleanup", report["cleanup"]["completed"])
    check("real_provider_request_observed", any(call.get("status") == 200 for call in observer.records))
    report["status"] = "PASSED" if checks and all(item["passed"] for item in checks) else "FAILED"
    quality = report["qualityChecks"] + report.get("questionQualityChecks", [])
    report["qualityStatus"] = "PASSED" if report["qualityChecks"] and report.get("questionQualityChecks") and all(item["passed"] for item in quality) else "FAILED"
    return report
