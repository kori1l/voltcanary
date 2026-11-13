"""Isolated durable-chat compound review, confirmed draft, and browser handoff."""
import argparse
import json
from pathlib import Path
import uuid

from ai_dashboard_eval.account_lock import AccountLease
from ai_dashboard_eval.api import ApiError, ROOT, authenticate, sha, write_json
from ai_dashboard_eval.conversations import ConversationRunner
from ai_dashboard_eval.execution import archive_dashboard
from ai_dashboard_eval.fixtures import VehicleFixture
from ai_dashboard_eval.grading import definition, grade


def cleanup(client, report):
    if report.get("job") != "STAGE_FOUR_EXECUTION" or not report.get("marker", "").startswith("EVAL_"):
        raise ValueError("Not an isolated stage-four handoff")
    dashboard = report.get("dashboardId")
    if dashboard:
        draft = client.call("GET", f"/dashboards/{dashboard}/draft")
        if draft["definition"]["name"] != report["name"] or not report["name"].endswith(report["marker"]):
            raise ValueError("Test dashboard identity mismatch")
        archive_dashboard(client, dashboard)
    for device in report.get("fixture", {}).get("deviceIds", []):
        value = client.call("GET", "/devices/" + device)
        if not value["name"].startswith(report["marker"]):
            raise ValueError("Test device identity mismatch")
        client.call("POST", f"/devices/{device}/decommission")
    return {"status": "PASSED", "cleanup": True, "dashboardId": dashboard}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cleanup-handoff", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if ROOT / "artifacts" not in output.parents:
        parser.error("Output must be under artifacts/")
    account = json.loads(args.account.read_text(encoding="utf-8-sig"))
    client, _ = authenticate("http://127.0.0.1:8080", account)
    with AccountLease(account["accountId"], "STAGE_FOUR_EXECUTION"):
        if args.cleanup_handoff:
            report = cleanup(client, json.loads(args.cleanup_handoff.read_text(encoding="utf-8")))
        else:
            marker = "EVAL_" + uuid.uuid4().hex[:8] + "_stage4_browser"
            name = "QA stage4 " + marker
            fixture = VehicleFixture(client, marker)
            report = {"job": "STAGE_FOUR_EXECUTION", "marker": marker, "name": name, "checks": [], "synthetic": True}
            report["model"] = {key: account[key] for key in ("providerId", "modelConfigId", "modelId")}
            service = ROOT / "backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/service"
            report["sourceHashes"] = {name: sha(service / name) for name in ("DashboardOperationSchema.java", "DashboardOperationCompiler.java", "DashboardCapabilityContext.java")}
            try:
                fixture.create()
                report["fixture"] = fixture.metadata()
                runner = ConversationRunner(client, account, output / "conversation")
                plan = runner.model(f"Создай дашборд «{name}» для выбранного транспорта. Нужны карта, текущая скорость, двери и следующая остановка отдельными карточками, графики скорости и дверей. Период для всего дашборда — последний час.", None, fixture.devices)
                report.update(planId=plan["planId"], conversationId=runner.diagnostics[-1]["conversationId"], requestId=runner.diagnostics[-1]["requestId"])
                initial = definition(plan)
                checks = grade(plan, {"name": name, "roles": ["map", "current:speed", "current:doors", "current:stop", "history:speed", "history:doors"],
                                     "views": {"history:doors": "line"}, "period": "LAST_1_HOUR"}, device_ids=fixture.devices)
                report["checks"].extend(checks)
                if not all(check["passed"] for check in checks):
                    raise ApiError(0, "INITIAL_REVIEW_FAILED")
                plan = runner.model("Перемести только карточки текущей скорости, дверей и следующей остановки наверх в одну строку. У карты скрой всю дополнительную информацию: подробности, сведения об измерениях и уведомления об устаревании. Остальные данные, графики и размеры сохрани.", plan, fixture.devices)
                report.update(planId=plan["planId"], conversationId=runner.diagnostics[-1]["conversationId"], requestId=runner.diagnostics[-1]["requestId"])
                checks = grade(plan, {"name": name, "roles": ["map", "current:speed", "current:doors", "current:stop", "history:speed", "history:doors"],
                                     "top": ["current:speed", "current:doors", "current:stop"], "views": {"history:doors": "line"},
                                     "presentation": {"map": {"showMapDetails": False, "showMapNotices": False, "showDetails": False}}, "allowReflow": True,
                                     "preserve": {"map": ["/presentation/showMapDetails", "/presentation/showMapNotices", "/presentation/showDetails"]}},
                               previous=initial, device_ids=fixture.devices)
                report["checks"].extend(checks)
                if not all(check["passed"] for check in checks):
                    raise ApiError(0, "COMPOUND_REVIEW_FAILED")
                reviewed = definition(plan)
                inventory_before = client.call("GET", "/dashboards?limit=100&status=ALL")
                report.update(planId=plan["planId"], reviewedDefinition=reviewed,
                              conversationId=runner.diagnostics[-1]["conversationId"], requestId=runner.diagnostics[-1]["requestId"])
                recovered = client.call("GET", "/ai-agent/plans/" + plan["planId"])
                report["checks"].append({"id": "reload_exact", "passed": definition(recovered) == reviewed and recovered["payloadDigest"] == plan["payloadDigest"]})
                body = {"confirmed": True, "expectedVersion": plan["version"], "payloadDigest": plan["payloadDigest"]}
                executed = client.call("POST", f"/ai-agent/plans/{plan['planId']}/execute", body)
                if executed.get("status") != "SUCCESS":
                    raise ApiError(0, "CONFIRMED_EXECUTION_FAILED")
                dashboard = executed["actions"][0]["result"]["dashboardId"]
                report["dashboardId"] = dashboard
                draft = client.call("GET", f"/dashboards/{dashboard}/draft")
                report["checks"].append({"id": "reviewed_created_exact", "passed": draft["definition"] == reviewed})
                replay = client.call("POST", f"/ai-agent/plans/{plan['planId']}/execute", body)
                report["checks"].append({"id": "idempotent_execution", "passed": replay["actions"][0]["result"]["dashboardId"] == dashboard})
                inventory_after = client.call("GET", "/dashboards?limit=100&status=ALL")
                def inventory(page):
                    return [{key: item.get(key) for key in ("id", "updatedAt", "status", "publishedRevision")}
                            for item in page.get("items", []) if item["id"] != dashboard]
                report["checks"].append({"id": "existing_dashboards_unchanged", "passed": not inventory_before.get("hasNext")
                                        and not inventory_after.get("hasNext") and inventory(inventory_before) == inventory(inventory_after)})
                detail = client.call("GET", "/ai-agent/conversations/" + report["conversationId"])
                write_json(output / "execution-trace.json", client.call("GET", f"/ai-agent/conversations/{report['conversationId']}/turns/{report['requestId']}/trace/export"))
                report["checks"].append({"id": "history_execution_recovery", "passed": detail["plan"]["execution"]["status"] == "SUCCESS"})
                report["status"] = "PASSED" if all(check["passed"] for check in report["checks"]) else "FAILED"
                report["keptForBrowser"] = True
            except ApiError as error:
                report.update(status="FAILED", errorCode=error.code, httpStatus=error.status)
            finally:
                if report.get("status") != "PASSED":
                    if report.get("dashboardId"):
                        archive_dashboard(client, report["dashboardId"])
                    report["cleanupFailures"] = fixture.cleanup()
    write_json(output / "summary.json", report)
    print(json.dumps({key: report.get(key) for key in ("status", "errorCode", "dashboardId", "conversationId", "keptForBrowser", "cleanup")}))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
