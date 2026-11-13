"""Real HSL creation/connection and durable dashboard referents; no injected readings."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

from ai_dashboard_eval.account_lock import AccountLease
from ai_dashboard_eval.api import ApiError, ROOT, authenticate, write_json
from ai_dashboard_eval.execution import archive_dashboard


class Chat:
    def __init__(self, client, account, output):
        self.api, self.account, self.output = client, account, output
        self.id = str(uuid.uuid4())
        self.api.call("POST", "/ai-agent/conversations", {"conversationId": self.id})
        self.plan = None
        self.turns = []

    def send(self, text, context=None, fresh=False):
        detail = self.api.call("GET", "/ai-agent/conversations/" + self.id)
        parent = None if fresh else self.plan
        continuation = parent and parent.get("executionStatus") not in {"SUCCESS", "FAILED", "UNKNOWN", "PARTIAL_SUCCESS", "EXPIRED", "RUNNING"}
        task = (parent.get("task") or {}) if continuation else {}
        task_id, request = task.get("taskId") or str(uuid.uuid4()), str(uuid.uuid4())
        body = {"requestId": request, "expectedRevision": detail["conversation"]["revision"], "text": text,
                "context": context or {}, "providerId": self.account["providerId"], "modelConfigId": self.account["modelConfigId"],
                "metadata": {"requestId": request, "taskId": task_id, "branchId": task.get("branchId") or task_id,
                             "expectedTaskRevision": task.get("revision", 0)}}
        if continuation:
            body["parentPlanId"] = parent["planId"]
        response = self.api.call("POST", f"/ai-agent/conversations/{self.id}/messages", body)
        self.plan = response.get("plan")
        record = self.capture(request, response)
        self.turns.append(record)
        return response, record

    def capture(self, request, detail, suffix=""):
        events = self.api.call("GET", f"/ai-agent/conversations/{self.id}/turns/{request}/trace/export")
        messages = self.api.call("GET", f"/ai-agent/conversations/{self.id}/messages?after=0&limit=100")
        path = self.output / self.id / (request + suffix + ".json")
        write_json(path, {"detail": detail, "trace": events, "messages": messages})
        return {"conversationId": self.id, "requestId": request, "pendingStatus": detail.get("pendingStatus"),
                "planId": (detail.get("plan") or {}).get("planId"),
                "modelCalls": sum(event["stage"] == "MODEL_CALL" and event["status"] == "STARTED" for event in events),
                "failures": [event for event in events if event["status"] in ("FAILED", "UNKNOWN")],
                "artifact": str(path.relative_to(self.output))}

    def execute(self):
        plan = self.plan
        body = {"confirmed": True, "expectedVersion": plan["version"], "payloadDigest": plan["payloadDigest"]}
        result = self.api.call("POST", f"/ai-agent/plans/{plan['planId']}/execute", body)
        detail = self.api.call("GET", "/ai-agent/conversations/" + self.id)
        self.plan = detail.get("plan")
        self.capture(self.turns[-1]["requestId"], detail, "-after-execution")
        return result


def live_keys():
    code = """
import json,threading,uuid
import paho.mqtt.client as mqtt
seen=set();done=threading.Event()
client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id='voltcanary-c3-'+uuid.uuid4().hex[:10]);client.tls_set()
def connected(client,*args):client.subscribe('/hfp/v2/journey/ongoing/vp/bus/#',qos=0)
def observed(client,userdata,message):
 parts=message.topic.split('/')
 if len(parts)>8 and parts[6]=='bus':
  seen.add(parts[7]+'-'+parts[8])
  if len(seen)>=8:done.set()
client.on_connect=connected;client.on_message=observed
client.connect_async('mqtt.hsl.fi',8883,keepalive=30);client.loop_start();done.wait(20)
client.disconnect();client.loop_stop();print(json.dumps(sorted(seen)))
"""
    run = subprocess.run(["docker", "compose", "exec", "-T", "hsl-connector", "python", "-"],
                         input=code, text=True, capture_output=True, timeout=35, cwd=ROOT)
    if run.returncode:
        raise ApiError(0, "PUBLIC_HSL_DISCOVERY_UNAVAILABLE")
    return json.loads(run.stdout)


def definition(plan):
    actions = (plan or {}).get("actions", [])
    return actions[0].get("parameters", {}).get("definition") if len(actions) == 1 and actions[0]["tool"] == "CREATE_DASHBOARD" else None


def delete_owned_device(client, device, marker):
    current = client.call("GET", "/devices/" + device["deviceId"])
    if current["name"] != device["name"] or not current["name"].startswith(marker):
        raise ValueError("QA device identity mismatch")
    receipt = client.call("POST", f"/devices/{device['deviceId']}/deletion", {"deleteHistory": False})
    deadline = time.monotonic() + 120
    while receipt["status"] != "COMPLETED":
        if time.monotonic() > deadline:
            raise ApiError(0, "QA_CLEANUP_TIMEOUT")
        time.sleep(2)
        receipt = client.call("GET", f"/devices/{device['deviceId']}/deletion")
    device["deleted"] = True


def validate_bindings(document, device, sources, capabilities):
    checks = {"exactRootDevice": document["defaultContext"]["scope"].get("deviceIds") == [device]}
    allowed_fields = {choice["key"] for choice in capabilities["choices"] if choice.get("key")}
    selected_devices, selected_sources, measured_fields = [], [], []

    def visit(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key == "deviceIds" and isinstance(item, list):
                    selected_devices.extend(item)
                elif key == "deviceId" and isinstance(item, str):
                    selected_devices.append(item)
                elif key == "sourceId" and isinstance(item, str):
                    selected_sources.append(item)
                elif key in ("vehicleField", "metric") and isinstance(item, str):
                    measured_fields.append(item)
                visit(item)
        elif isinstance(value, list):
            for item in value:
                visit(item)
    visit(document)
    checks["allBindingsUseSelectedDevice"] = bool(selected_devices) and set(selected_devices) == {device}
    checks["allSourcesBelongToCreatedDevice"] = set(selected_sources) <= set(sources)
    checks["onlyActualFields"] = set(measured_fields) <= allowed_fields
    def has_coordinate_field(value):
        if isinstance(value, dict):
            return any(key in ("vehicleField", "metric") and item in ("latitude", "longitude")
                       or has_coordinate_field(item) for key, item in value.items())
        return isinstance(value, list) and any(has_coordinate_field(item) for item in value)
    checks["noCoordinateGraphs"] = all(not has_coordinate_field(widget) for widget in document["widgets"]
                                       if widget["type"] in ("vehicle-timeline", "time-series"))
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--keep-for-browser", action="store_true")
    parser.add_argument("--cleanup-handoff", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if ROOT / "artifacts" not in output.parents:
        parser.error("Output must be under artifacts/")
    account = json.loads(args.account.read_text(encoding="utf-8-sig"))
    client, _ = authenticate("http://127.0.0.1:8080", account)
    if args.cleanup_handoff:
        saved = json.loads(args.cleanup_handoff.read_text(encoding="utf-8"))
        if not saved.get("marker", "").startswith("QA C3 "):
            parser.error("Not a C3 QA handoff")
        with AccountLease(account["accountId"], "CREATED_DEVICE_CLEANUP"):
            for dashboard in saved["dashboards"]:
                archive_dashboard(client, dashboard)
            for device in saved["devices"]:
                if not device.get("deleted"):
                    delete_owned_device(client, device, saved["marker"])
            write_json(output / "summary.json", {"status": "PASSED", "source": str(args.cleanup_handoff), "cleanupCompleted": True})
        print("C3 QA cleanup: PASS")
        return 0
    marker = "QA C3 " + uuid.uuid4().hex[:8]
    report = {"marker": marker, "injectedReadings": False, "checks": [], "turns": [], "devices": [], "dashboards": [], "status": "RUNNING"}

    def check(name, passed, **details):
        report["checks"].append({"id": name, "passed": bool(passed), **details})
        write_json(output / "summary.json", report)
        print(name + ": " + ("PASS" if passed else "FAIL"), flush=True)
        if not passed:
            raise ApiError(0, name.upper().replace("-", "_"))

    with AccountLease(account["accountId"], "CREATED_DEVICE_CONTINUATION"):
        try:
            original = Chat(client, account, output)
            request = (f'Создай в VoltCanary устройство для реального автобуса HSL и подключи получение телеметрии. Название: «{marker} original». '
                       'Тип устройства: VEHICLE. Вид транспорта: bus. Источник: MQTT mqtt.hsl.fi:8883, TLS с проверкой сертификата, анонимное подключение. '
                       'Топик: /hfp/v2/journey/ongoing/vp/bus/0018/01092/#. Используй HSL-коннектор и API/REST. Сохрани подключения других машин. '
                       'Проверь несколько свежих показаний. Не выдумывай данные.')
            response, record = original.send(request)
            report["turns"].append(record)
            actions = (response.get("plan") or {}).get("actions", [])
            check("original-hsl-intent", len(actions) == 1 and actions[0]["tool"] == "CREATE_DEVICE"
                  and actions[0]["parameters"].get("connection") == {"provider": "HSL_HFP", "mode": "bus", "vehicleKey": "0018-01092"})
            proposed = response["plan"]["planId"]
            response, record = original.send('Создай для этого автобуса дашборд «' + marker + ' proposed».')
            report["turns"].append(record)
            check("unconfirmed-device-not-a-referent", (response.get("plan") or {}).get("planId") == proposed and record["modelCalls"] == 0
                  and response.get("pendingStatus") == "COMPLETED")
            keys = live_keys()
            report["publicLiveKeysObserved"] = keys
            check("live-hsl-vehicles-available", len(keys) >= 2)
            chat = Chat(client, account, output)
            for index, key in enumerate(keys[:2]):
                name = marker + (" A" if index == 0 else " B")
                response, record = chat.send(f'Создай один реальный автобус HSL «{name}», тип VEHICLE, борт {key}, bus. Подключи телеметрию через штатный HSL-коннектор. Без симулятора.', fresh=True)
                report["turns"].append(record)
                actions = (response.get("plan") or {}).get("actions", [])
                check("live-device-review-" + str(index), len(actions) == 1 and actions[0]["tool"] == "CREATE_DEVICE"
                      and actions[0]["parameters"].get("name") == name)
                execution = chat.execute()
                check("live-device-execution-" + str(index), execution.get("status") == "SUCCESS")
                result = execution["actions"][0]["result"]
                device = result["deviceId"]
                report["devices"].append({"deviceId": device, "name": name, "sourceId": result.get("sourceId"), "dataSourceId": result.get("dataSourceId"), "setupStatus": result.get("setupStatus")})
                before = client.call("GET", f"/ai-agent/conversations/{chat.id}/messages?after=0&limit=100")
                restored = client.call("GET", "/ai-agent/conversations/" + chat.id)
                after = client.call("GET", f"/ai-agent/conversations/{chat.id}/messages?after=0&limit=100")
                check("reload-read-only-" + str(index), before == after and restored["plan"]["execution"]["status"] == "SUCCESS")
                chat.plan = restored["plan"]
                capabilities = client.call("POST", "/dashboards/composition/capabilities", {"deviceIds": [device], "period": "LAST_1_HOUR"})
                write_json(output / ("capabilities-" + str(index) + ".json"), capabilities)
                response, record = chat.send('Теперь создай для этого автобуса дашборд «' + marker + ' обзор ' + str(index) + '» со всеми полезными доступными данными: карта и подходящие показатели/графики. Используй только реальные поля; если данных пока нет, скажи об этом.')
                report["turns"].append(record)
                document = definition(response.get("plan"))
                check("grounded-dashboard-review-" + str(index), document is not None)
                artifact = json.loads((output / record["artifact"]).read_text(encoding="utf-8"))
                check("confirmed-receipt-resolution-" + str(index), any(event["stage"] == "REFERENT" and event.get("code") == "CONFIRMED_EXECUTION" for event in artifact["trace"]))
                capabilities = client.call("POST", "/dashboards/composition/capabilities", {"deviceIds": [device], "period": "LAST_1_HOUR"})
                write_json(output / ("capabilities-after-" + str(index) + ".json"), capabilities)
                for key_check, passed in validate_bindings(document, device, [result.get("sourceId"), result.get("dataSourceId")], capabilities).items():
                    check(key_check + "-" + str(index), passed)
                execution = chat.execute()
                check("grounded-dashboard-execution-" + str(index), execution.get("status") == "SUCCESS")
                dashboard = execution["actions"][0]["result"]["dashboardId"]
                report["dashboards"].append(dashboard)
                draft = client.call("GET", f"/dashboards/{dashboard}/draft")
                check("reviewed-created-equal-" + str(index), draft["definition"] == document)
            selected = report["devices"][0]
            response, record = chat.send('Создай новый дашборд «' + marker + ' explicit» с картой выбранного автобуса.',
                                         {"deviceId": selected["deviceId"]}, fresh=True)
            report["turns"].append(record)
            document = definition(response.get("plan"))
            check("explicit-device-overrides-most-recent", document is not None and document["defaultContext"]["scope"]["deviceIds"] == [selected["deviceId"]])
            archive_dashboard(client, report["dashboards"][-1])
            delete_owned_device(client, report["devices"][-1], marker)
            previous_plan = chat.plan["planId"]
            response, record = chat.send('Создай для этого автобуса новый дашборд «' + marker + ' deleted».', fresh=True)
            report["turns"].append(record)
            check("deleted-referent-no-fallback", record["modelCalls"] == 0 and response.get("pendingStatus") == "FAILED"
                  and (response.get("plan") or {}).get("planId") == previous_plan)
            report.update(status="PASSED", conversationId=chat.id, keptForBrowser=args.keep_for_browser)
        except ApiError as error:
            report.update(status="FAILED", errorCode=error.code, httpStatus=error.status)
        finally:
            if not args.keep_for_browser or report["status"] != "PASSED":
                for dashboard in report["dashboards"]:
                    archive_dashboard(client, dashboard)
                for device in report["devices"]:
                    if not device.get("deleted"):
                        delete_owned_device(client, device, marker)
                report["cleanupRequested"] = True
            write_json(output / "summary.json", report)
    print(json.dumps({key: report.get(key) for key in ("status", "errorCode", "conversationId", "keptForBrowser", "cleanupRequested")}))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
