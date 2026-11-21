"""Separate explicit fixture execution and shared-renderer baseline jobs."""
from contextlib import ExitStack
from copy import deepcopy
import uuid

from .api import ApiError
from .conversations import ConversationRunner
from .fixtures import VehicleFixture
from .grading import definition, grade


def archive_dashboard(client, dashboard_id):
    draft = client.call("GET", f"/dashboards/{dashboard_id}/draft")
    client.call("POST", f"/dashboards/{dashboard_id}/archive", headers={"If-Match": '"' + draft["etag"] + '"'})


def execute_dashboard_lifecycle(client, account, output):
    marker = "EVAL_" + uuid.uuid4().hex + "_lifecycle"
    name = "QA " + marker
    updated_name = name + " updated"
    fixture = VehicleFixture(client, marker)
    runner = ConversationRunner(client, account, output)
    report = {"job": "CONFIRMED_DASHBOARD_LIFECYCLE", "synthetic": True, "marker": marker,
              "checks": [], "diagnostics": runner.diagnostics, "dashboardId": None}
    dashboard = None

    def check(code, passed):
        report["checks"].append({"id": code, "passed": passed})
        if not passed:
            raise ApiError(0, code)

    def confirm(plan):
        return client.call("POST", f"/ai-agent/plans/{plan['planId']}/execute",
                           {"confirmed": True, "expectedVersion": plan["version"], "payloadDigest": plan["payloadDigest"]})

    def inventory():
        page = client.call("GET", "/dashboards?limit=100&status=ALL")
        if page.get("hasNext"):
            raise ApiError(0, "BOUNDED_ACCOUNT_REQUIRED")
        return {item["id"]: item for item in page["items"] if item["id"] != dashboard}

    try:
        baseline = inventory()
        fixture.create()
        report["fixture"] = fixture.metadata()
        plan = runner.model(f"Создай дашборд «{name}» с одной карточкой текущей скорости. "
                            "Глобальный период всего дашборда — последний час.", None, fixture.devices)
        predicates = grade(plan, {"name": name, "roles": ["current:speed"], "period": "LAST_1_HOUR"},
                           device_ids=fixture.devices)
        report["createSemantics"] = predicates
        reviewed = definition(plan)
        check("CREATE_SEMANTICS", all(item["passed"] for item in predicates) and len(reviewed["widgets"]) == 1)
        report["createReview"] = reviewed
        check("CREATE_WAITS_FOR_CONFIRMATION", inventory() == baseline)
        created = confirm(plan)
        check("CREATE_CONFIRMED", created.get("status") == "SUCCESS")
        dashboard = created["actions"][0]["result"]["dashboardId"]
        report["dashboardId"] = dashboard
        draft_path = f"/dashboards/{dashboard}/draft"
        check("CREATE_READBACK_EXACT", client.call("GET", draft_path)["definition"] == reviewed)

        update = runner.model(f"Переименуй сохранённый черновик дашборда в «{updated_name}». Остальное сохрани.",
                              plan, fixture.devices,
                              independent=True, executed_base=True)
        expected = {**reviewed, "name": updated_name}
        candidate = definition(update)
        report["updateReview"] = candidate
        check("UPDATE_SEMANTICS", update.get("planningStatus") == "READY" and candidate == expected)
        check("UPDATE_WAITS_FOR_CONFIRMATION", client.call("GET", draft_path)["definition"] == reviewed)
        saved = confirm(update)
        check("UPDATE_CONFIRMED_SAME_ID", saved.get("status") == "SUCCESS"
              and saved["actions"][0]["result"]["dashboardId"] == dashboard)
        check("UPDATE_READBACK_EXACT", client.call("GET", draft_path)["definition"] == candidate)
        check("UNRELATED_DASHBOARDS_UNCHANGED", inventory() == baseline)
        report["status"] = "PASSED"
    except ApiError as error:
        report.update(status="FAILED", errorCode=error.code, httpStatus=error.status)
    finally:
        failures = []
        if dashboard:
            try:
                archive_dashboard(client, dashboard)
            except ApiError as error:
                failures.append({"dashboardId": dashboard, "code": error.code, "httpStatus": error.status})
        failures.extend(fixture.cleanup())
        report["cleanup"] = {"completed": not failures, "failures": failures}
        if failures and report.get("status") == "PASSED":
            report.update(status="FAILED", errorCode="FIXTURE_CLEANUP_FAILED")
    return report


def execute_fixture(client, account, renderer_reference=False, keep=False):
    marker = "EVAL_" + uuid.uuid4().hex[:8] + "_execution_t1"
    report = {"job": "RENDERER_REFERENCE" if renderer_reference else "CONFIRMED_MODEL_EXECUTION",
              "marker": marker, "synthetic": True, "dashboardId": None, "fixtures": [],
              "checks": [], "keptForBrowser": keep}
    created = None
    fixtures = []
    try:
        profiles = ("normal", "stale", "empty") if renderer_reference else ("normal",)
        for index, profile in enumerate(profiles):
            fixture = VehicleFixture(client, marker + "_" + str(index), profile)
            fixtures.append(fixture)
            fixture.create()
            report["fixtures"].append(fixture.metadata())
        if renderer_reference:
            prepared = client.call("POST", "/dashboards/composition/prepare", reference_intent(marker, fixtures))
            if prepared.get("status") != "READY":
                raise ApiError(0, "REFERENCE_PREPARATION_FAILED")
            reviewed = prepared["definition"]
            created = client.call("POST", "/dashboards", {"definition": reviewed},
                                  {"Idempotency-Key": "eval-renderer-" + str(uuid.uuid4())})
        else:
            fixture = fixtures[0]
            name = "QA execution " + marker
            plan = client.call("POST", "/ai-agent/plans", {
                "message": f"Создай дашборд «{name}» для выбранного транспорта. Только одна карточка текущей скорости, период последний час.",
                "context": {"deviceIds": fixture.devices}, "providerId": account["providerId"],
                "modelConfigId": account["modelConfigId"]})
            report["planId"] = plan["planId"]
            checks = grade(plan, {"name": name, "roles": ["current:speed"]}, device_ids=fixture.devices)
            report["checks"] += checks
            if not all(c["passed"] for c in checks):
                raise ApiError(0, "MODEL_DID_NOT_PREPARE_EXECUTION_FIXTURE")
            reviewed = definition(plan)
            recovered = client.call("GET", "/ai-agent/plans/" + plan["planId"])
            report["checks"].append({"id": "review_recovery_exact", "passed": recovered["payloadDigest"] == plan["payloadDigest"] and definition(recovered) == reviewed})
            response = client.call("POST", f"/ai-agent/plans/{plan['planId']}/execute", {
                "confirmed": True, "expectedVersion": plan["version"], "payloadDigest": plan["payloadDigest"]})
            if response.get("status") != "SUCCESS":
                raise ApiError(0, "EXECUTION_NOT_SUCCESSFUL")
            result = response["actions"][0]["result"]
            created = {"id": result["dashboardId"]}
            replay = client.call("POST", f"/ai-agent/plans/{plan['planId']}/execute", {
                "confirmed": True, "expectedVersion": plan["version"], "payloadDigest": plan["payloadDigest"]})
            report["checks"].append({"id": "execution_replay_same_draft", "passed": replay["actions"][0]["result"]["dashboardId"] == created["id"]})
        report["dashboardId"] = created["id"]
        draft = client.call("GET", f"/dashboards/{created['id']}/draft")
        report["checks"].append({"id": "exact_reviewed_definition", "passed": draft["definition"] == reviewed})
        report["reviewedDefinition"] = deepcopy(reviewed)
        if renderer_reference:
            # Private publication is confined to this disposable renderer fixture.
            client.call("POST", f"/dashboards/{created['id']}/publish", {"draftEtag": draft["etag"]},
                        {"If-Match": '"' + draft["etag"] + '"', "Idempotency-Key": str(uuid.uuid4())})
        report["status"] = "PASSED" if all(c["passed"] for c in report["checks"]) else "FAILED"
    except ApiError as error:
        report.update(status="FAILED", errorCode=error.code, httpStatus=error.status)
    finally:
        if not keep or report.get("status") != "PASSED":
            cleanup = []
            if created:
                try:
                    archive_dashboard(client, created["id"])
                except ApiError as error:
                    cleanup.append({"dashboardId": created["id"], "status": error.status})
            for fixture in fixtures:
                cleanup += fixture.cleanup()
            report["cleanup"] = {"completed": not cleanup, "failures": cleanup}
        else:
            report["cleanup"] = {"completed": False, "reason": "EXPLICIT_BROWSER_HANDOFF"}
    return report


def reference_intent(marker, fixtures):
    normal, stale, empty = fixtures

    def panel(key, typ, title, field=None, fixture=normal, width="THIRD", height=1, **settings):
        value = {"key": key, "type": typ, "title": title, "width": width, "height": height,
                 "deviceIds": fixture.devices, "sourceId": fixture.sources[0]["id"],
                 "settings": {"showTitle": True, **settings}}
        if field:
            value["vehicleField"] = field
        return value

    widgets = [panel("map", "vehicle-map", "Синтетическая карта", width="FULL", height=5,
                     showMapDetails=False, showMapNotices=False),
               panel("speed", "vehicle-value", "Скорость — ноль", "speedKmh", compact=True),
               panel("doors", "vehicle-value", "Двери — закрыты", "transport.doorsOpen", compact=True,
                     trueLabel="Открыты", falseLabel="Закрыты"),
               panel("stop", "vehicle-value", "Следующая остановка", "transport.nextStopId", compact=True),
               {**panel("history", "vehicle-timeline", "Записанные состояния дверей", "transport.doorsOpen",
                        width="FULL", height=4), "visualization": "line"},
               panel("stale", "vehicle-value", "Устаревшее наблюдение", "speedKmh", stale, "FULL", 2),
               {**panel("empty", "vehicle-timeline", "Пустой интервал", "speedKmh", empty, "FULL", 3),
                "visualization": "line", "period": "LAST_1_HOUR"},
               {"key": "notice", "type": "context-note", "title": "Источник baseline",
                "text": "Синтетические наблюдения. Эталон проверки рендера, не результат AI.", "width": "FULL", "height": 2}]
    return {"name": "QA renderer " + marker, "deviceIds": [d for f in fixtures for d in f.devices],
            "period": "LAST_24_HOURS", "widgets": widgets}


def cleanup_handoff(client, report):
    """Require exact fixture identities/names; never clean arbitrary report targets."""
    marker = report["marker"]
    if not marker.startswith("EVAL_") or report.get("job") != "RENDERER_REFERENCE":
        raise ValueError("Not an owned renderer handoff report")
    draft = client.call("GET", f"/dashboards/{report['dashboardId']}/draft")
    if draft["definition"]["name"] != "QA renderer " + marker:
        raise ValueError("Dashboard name does not match this fixture")
    for fixture in report["fixtures"]:
        for device in fixture["deviceIds"]:
            value = client.call("GET", "/devices/" + device)
            if not value["name"].startswith(marker + "_"):
                raise ValueError("Device is not owned by this fixture")
    archive_dashboard(client, report["dashboardId"])
    for fixture in report["fixtures"]:
        for device in fixture["deviceIds"]:
            client.call("POST", f"/devices/{device}/decommission")
    report["cleanup"] = {"completed": True, "failures": []}
    return report
