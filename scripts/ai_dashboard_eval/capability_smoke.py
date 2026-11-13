"""Read/preparation contract checks against measured disposable fixtures; no model calls."""
import os
import uuid

from .api import ApiError, wait_until
from .fixtures import VehicleFixture


def run_capability_smoke(client):
    report = {"job": "CAPABILITY_CONTRACT", "checks": [], "fixtures": []}
    before = client.call("GET", "/dashboards?limit=100&status=ALL")

    def check(name, passed):
        report["checks"].append({"id": name, "passed": bool(passed)})

    def resolve(device, **extra):
        return client.call("POST", "/dashboards/composition/capabilities", {"deviceIds": [device], "period": "LAST_1_HOUR", **extra})

    def rejected(body, status=422, token=None, path="prepare"):
        try:
            client.call("POST", "/dashboards/composition/" + path, body, token=token)
        except ApiError as error:
            return error.status == status
        return False

    for profile in ("normal", "ambiguous", "stale"):
        fixture = VehicleFixture(client, "EVAL_" + uuid.uuid4().hex[:8] + "_capability", profile)
        try:
            with fixture:
                device = fixture.devices[0]
                result = wait_until(lambda: resolve(device), lambda value: any(choice.get("key") == "transport.doorsOpen" for choice in value["choices"]))
                report["fixtures"].append(fixture.metadata())
                choices = result["choices"]
                check(profile + ":no_raw_readings", all("value" not in c and "position" not in c for c in choices))
                doors = [c for c in choices if c.get("key") == "transport.doorsOpen" and c["family"] == "RECORDED_VEHICLE_FIELD"]
                check(profile + ":observed_false_not_missing", bool(doors) and all(c["valueType"] == "boolean" and c["currentStatus"] == "OBSERVED" for c in doors))
                check(profile + ":boolean_current_and_line", all(any(v["widgetType"] == "vehicle-value" for v in c["views"]) and any(v["widgetType"] == "vehicle-timeline" and "line" in v["visualizations"] for v in c["views"]) for c in doors))
                stops = [c for c in choices if c.get("key") == "transport.nextStopId" and c["family"] == "RECORDED_VEHICLE_FIELD"]
                check(profile + ":text_history_list_only", bool(stops) and all(v["visualizations"] == ["list"] for c in stops for v in c["views"] if v["widgetType"] == "vehicle-timeline"))
                check(profile + ":history_availability_not_invented", all(c["historyStatus"] == "UNKNOWN" for c in choices))
                if profile == "stale":
                    check("stale:source_age_retained", all(c["sourceFreshness"] == "STALE" for c in doors))
                body = {"name": "QA capabilities", "period": "LAST_1_HOUR", "deviceIds": [device],
                        "widgets": [{"key": "doors", "type": "vehicle-timeline", "title": "Doors", "width": "FULL", "height": 3,
                                     "vehicleField": "transport.doorsOpen", "visualization": "line"}]}
                if profile == "ambiguous":
                    check("ambiguous:explicit_diagnostic", any(d["code"] == "AMBIGUOUS_SOURCE" for d in result["diagnostics"]))
                    check("ambiguous:no_first_source_default", rejected(body))
                body["widgets"][0]["sourceId"] = fixture.sources[0]["id"]
                prepared = client.call("POST", "/dashboards/composition/prepare", body)
                check(profile + ":ordinary_boolean_preparation", prepared["status"] == "READY")
                widget = prepared.get("definition", {}).get("widgets", [{}])[0]
                check(profile + ":actual_source_identity", widget.get("query", {}).get("sourceId") == fixture.sources[0]["externalId"])
                body["widgets"][0]["vehicleField"] = "transport.nextStopId"
                check(profile + ":text_line_rejected", rejected(body))
                check(profile + ":foreign_not_found", rejected({"deviceIds": [device], "period": "LAST_1_HOUR"}, 404,
                      token=os.environ["VOLT_EVAL_FOREIGN_ACCESS_TOKEN"], path="capabilities"))
        except ApiError as error:
            report.setdefault("failures", []).append({"profile": profile, "httpStatus": error.status, "code": error.code})
            check(profile + ":http_contract", False)
        finally:
            report.setdefault("cleanup", []).append({"completed": not getattr(fixture, "cleanup_failures", ["UNKNOWN"]), "failures": getattr(fixture, "cleanup_failures", [])})
    report["noDashboardMutation"] = before == client.call("GET", "/dashboards?limit=100&status=ALL")
    report["status"] = "PASSED" if all(c["passed"] for c in report["checks"]) and all(c["completed"] for c in report["cleanup"]) and report["noDashboardMutation"] else "FAILED"
    return report
