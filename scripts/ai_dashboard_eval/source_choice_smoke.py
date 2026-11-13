"""Actual model source question, explicit choice, provider failure and durable continuation."""
import json
import subprocess
import uuid

from .api import ApiError, write_json
from .fixtures import VehicleFixture
from .grading import definition, grade


def run_source_choice_smoke(client, account, provider, observer, output):
    report = {"job": "SOURCE_CHOICE_MODEL", "checks": [], "qualityChecks": []}

    def check(name, passed):
        report["checks"].append({"id": name, "passed": bool(passed)})

    fixture = VehicleFixture(client, "EVAL_" + uuid.uuid4().hex[:8] + "_source_choice", "ambiguous")
    proxy = f"http://host.docker.internal:{observer.port}/v1"
    try:
        with fixture:
            report["fixture"] = fixture.metadata()
            task = str(uuid.uuid4())
            name = "QA source choice"
            body = {"message": f"Создай дашборд «{name}». Нужна только одна карточка текущего состояния дверей выбранного транспорта. Если есть несколько источников, спроси какой использовать.",
                    "context": {"name": name, "deviceIds": fixture.devices}, "providerId": account["providerId"], "modelConfigId": account["modelConfigId"],
                    "requestId": str(uuid.uuid4()), "taskId": task, "branchId": task, "expectedTaskRevision": 0}
            question = client.call("POST", "/ai-agent/plans", body)
            report.update(taskId=task, questionReviewId=question["planId"])
            check("actual_source_question", question.get("planningStatus") == "NEEDS_INPUT" and not question.get("actions"))
            clarification = question.get("clarification")
            check("no_automatic_source_default", clarification is None or clarification.get("requiresExplicitChoice") is True)
            actor = str(uuid.UUID(account["userId"]))
            scope = str(uuid.UUID(account["accountId"]))
            plan = str(uuid.UUID(question["planId"]))
            source = fixture.sources[1]
            source_uuid = str(uuid.UUID(source["id"]))
            sql = f"""select distinct r.summary_json::jsonb->>'sourceChoiceLabel' from ai_plan_references r
                      join ai_reviewed_plans p on p.id=r.plan_id where p.id='{plan}' and p.user_id='{actor}' and p.account_id='{scope}'
                      and r.kind='dashboard-data' and r.summary_json::jsonb->>'sourceId'='{source_uuid}'"""
            result = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "sh", "-c",
                    'psql -U "$POSTGRES_USER" -d voltcanary_ai_agent -tAc "$1"', "source-choice-metadata", sql], capture_output=True, text=True, check=True)
            labels = [line.strip() for line in result.stdout.splitlines() if line.strip()]
            if len(labels) != 1:
                check("scoped_choice_mapping", False)
                raise ApiError(0, "SOURCE_CHOICE_MAPPING_UNAVAILABLE")
            label = labels[0]
            report["selectedSource"] = {"id": source_uuid, "externalId": source["externalId"], "choiceLabel": label}
            check("choice_label_is_visible", label in question.get("explanation", ""))
            path = f"/ai-agent/plans/{question['planId']}/clarifications"
            answer = {"message": label, "requestId": str(uuid.uuid4()), "taskId": task, "branchId": task,
                      "expectedTaskRevision": question["task"]["revision"], "baseReviewId": question["planId"]}
            client.call("PUT", "/ai-agent/model-providers/" + provider["id"], {"baseUrl": "http://127.0.0.1:9/v1"})
            try:
                client.call("POST", path, answer)
                check("source_answer_provider_failure", False)
            except ApiError as error:
                check("source_answer_provider_failure", error.status == 502)
            finally:
                client.call("PUT", "/ai-agent/model-providers/" + provider["id"], {"baseUrl": proxy})
            recovered = client.call("GET", "/ai-agent/dashboard-tasks/" + task)
            check("name_and_original_request_survive", recovered["task"]["name"] == name and recovered["task"]["originalRequest"] == body["message"])
            device_uuid = str(uuid.UUID(fixture.devices[0]))
            sql = f"""select state_json::jsonb#>>'{{selection,sourcesByDevice,{device_uuid}}}'
                      from ai_dashboard_tasks where task_id='{task}' and branch_id='{task}'
                      and user_id='{actor}' and account_id='{scope}'"""
            persisted = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "sh", "-c",
                    'psql -U "$POSTGRES_USER" -d voltcanary_ai_agent -tAc "$1"', "source-checkpoint-metadata", sql], capture_output=True, text=True, check=True)
            check("source_checkpoint_survives_before_retry", persisted.stdout.strip() == source_uuid)
            retry = {**answer, "message": "Продолжай с сохранённым выбором источника.", "requestId": str(uuid.uuid4()),
                     "expectedTaskRevision": recovered["task"]["revision"]}
            ready = client.call("POST", path, retry)
            report["reviewId"] = ready["planId"]
            report["qualityChecks"] = grade(ready, {"name": name, "roles": ["current:doors"]}, device_ids=fixture.devices)
            doc = definition(ready)
            check("same_task_and_branch", ready.get("task", {}).get("taskId") == task and ready["task"]["branchId"] == task)
            check("selected_source_survives_provider_failure", bool(doc) and all(w.get("query", {}).get("sourceId") == source["externalId"] for w in doc["widgets"]))
            check("only_requested_current_value", bool(doc) and len(doc["widgets"]) == 1 and doc["widgets"][0]["type"] == "vehicle-value")
            report["definition"] = doc
    except ApiError as error:
        report["failure"] = {"httpStatus": error.status, "code": error.code}
    finally:
        report["cleanup"] = {"completed": not getattr(fixture, "cleanup_failures", ["UNKNOWN"]), "failures": getattr(fixture, "cleanup_failures", [])}
        report["providerObservations"] = list(observer.records)
        report["qualityStatus"] = "PASSED" if report["qualityChecks"] and all(c["passed"] for c in report["qualityChecks"]) else "FAILED"
        report["status"] = "PASSED" if not report.get("failure") and report["checks"] and all(c["passed"] for c in report["checks"]) and report["qualityChecks"] and all(c["passed"] for c in report["qualityChecks"]) and report["cleanup"]["completed"] else "FAILED"
        write_json(output, report)
    return report
