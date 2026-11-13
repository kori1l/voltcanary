"""Non-mutating configured-model regression: device dialogue and dashboard handoff."""
import argparse
import json
from pathlib import Path
from ai_dashboard_eval.api import authenticate, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device-id", required=True)
    args = parser.parse_args()
    account = json.loads(args.account.read_text(encoding="utf-8-sig"))
    client, _ = authenticate("http://localhost:8080", account)
    results = []

    def plan(message, context=None):
        return client.call("POST", "/ai-agent/plans", {"message": message, "context": context or {},
                           "providerId": account["providerId"], "modelConfigId": account["modelConfigId"]})

    def record(case, value, correct):
        results.append({"case": case, "passed": bool(correct), "plan": value})
        write_json(args.output, {"status": "RUNNING", "results": results})
        print(case, bool(correct), flush=True)

    value = plan("Создай реальный холодильник.\nНазвание: Северная кухня\nТип устройства: FRIDGE\nБез симулятора.")
    actions = value.get("actions", [])
    record("ordinary-device", value, len(actions) == 1 and actions[0]["tool"] == "CREATE_DEVICE"
           and actions[0]["parameters"].get("name") == "Северная кухня")
    value = plan("Создай одно реальное устройство типа VEHICLE. Как его назвать, спроси у меня. Источник подключим отдельно.")
    record("name-question", value, not value.get("actions"))
    value = client.call("POST", f'/ai-agent/plans/{value["planId"]}/clarifications', {"message": "Мой автобус 1"})
    actions = value.get("actions", [])
    record("plain-name-answer", value, len(actions) == 1 and actions[0]["parameters"].get("name") == "Мой автобус 1")
    value = plan("Создай в VoltCanary устройство для реального автобуса HSL и подключи получение телеметрии.\n"
                 "Название: HSL 67 — 0018-01092\nТип устройства: VEHICLE\nВид транспорта: bus\n"
                 "Источник: MQTT mqtt.hsl.fi:8883, TLS с проверкой сертификата, анонимное подключение.\n"
                 "Топик: /hfp/v2/journey/ongoing/vp/bus/0018/01092/#\n"
                 "Используй HSL-коннектор и API/REST. Сохрани подключения других машин. Проверь несколько свежих показаний. Не выдумывай данные.")
    actions = value.get("actions", [])
    record("hsl-original-request", value, len(actions) == 1 and actions[0]["parameters"].get("connection") ==
           {"provider": "HSL_HFP", "mode": "bus", "vehicleKey": "0018-01092"})
    value = plan("Создай дашборд «Проверка подключения» для выбранного автобуса. Добавь карту и текущую скорость.",
                 {"deviceId": args.device_id})
    actions = value.get("actions", [])
    record("dashboard-handoff", value, len(actions) == 1 and actions[0]["tool"] == "CREATE_DASHBOARD")
    write_json(args.output, {"status": "PASSED" if all(r["passed"] for r in results) else "FAILED", "results": results,
                            "modelId": account["modelId"], "mutations": "No execution requests sent"})
    return 0 if all(r["passed"] for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
