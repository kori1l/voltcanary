"""Populate an explicitly selected local demo account and publish its operational dashboard.

Password stays in DEMO_DASHBOARD_PASSWORD; no credentials or bearer tokens are saved.
Historical measurements are synthetic local fixtures, not production evidence.
"""
import argparse
import json
import math
import os
import subprocess
import uuid
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
import runpy

helpers = runpy.run_path(str(Path(__file__).with_name("workspace-dashboard-smoke.py")))
api, key, publish = (helpers[name] for name in ("api", "key", "publish"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--email", required=True)
    args = parser.parse_args()
    if not args.email.endswith("@example.invalid"):
        raise SystemExit("Select a reserved local demonstration account.")
    password = os.environ.get("DEMO_DASHBOARD_PASSWORD")
    if not password:
        raise SystemExit("Set DEMO_DASHBOARD_PASSWORD for this process.")
    actor = {"token": api("POST", "/auth/login", {"email": args.email, "password": password})["accessToken"]}
    account = api("GET", "/accounts/current", actor=actor)["id"]
    uuid.UUID(account)
    devices = api("GET", "/devices?limit=100", actor=actor)
    if isinstance(devices, dict):
        devices = devices.get("items", devices.get("devices", []))
    if not devices:
        location = api("POST", "/locations", {"name": "DEMO · Harbor workshop"}, actor)["id"]
        for name, kind in [("DEMO · Cooling station", "FRIDGE"), ("DEMO · Production washer", "WASHING_MACHINE"), ("DEMO · Workshop printer", "THREE_D_PRINTER"), ("DEMO · Circulation pump", "PUMP")]:
            devices.append(api("POST", "/devices", {"name": name, "type": kind, "locationId": location, "metadata": {"dataKind": "synthetic", "fixture": "harbor-dashboard"}}, actor))
    devices = devices[:20]
    now = datetime.now(timezone.utc).replace(second=0, microsecond=0)
    start = now - timedelta(days=14)
    iso = lambda value: value.isoformat().replace("+00:00", "Z")
    tuples = []
    for index, device in enumerate(devices):
        device_id = device["id"]
        uuid.UUID(device_id)
        external = "DEMO-dashboard-" + device_id
        sources = api("GET", f"/devices/{device_id}/data-sources", actor=actor)
        source = next((s for s in sources if s.get("externalId") == external), None)
        if not source:
            source = api("POST", f"/devices/{device_id}/data-sources", {"sourceType": "SMART_PLUG", "protocol": "REST", "externalId": external}, actor)
        source_id = source["id"]
        uuid.UUID(source_id)
        # Independent physical meters are explicitly declared for these synthetic fixtures.
        meter = api("GET", f"/devices/{device_id}/energy-meter", actor=actor)
        if not meter:
            api("PUT", f"/devices/{device_id}/energy-meter", {"expectedRevision": 0, "physicalMeterId": str(uuid.uuid4()), "sourceId": source_id, "authoritativeMethod": "COUNTER_DELTA", "maxGapSeconds": 1800, "effectiveFrom": iso(start), "counterEvents": []}, actor)
        watts = [110, 450, 700, 1200, 85, 900, 650, 160, 200, 95][index % 10]
        historical_source = meter[-1]["sourceId"] if meter else source_id
        uuid.UUID(historical_source)
        tuples.append(f"('{device_id}'::uuid,'{historical_source}'::text,{watts},{index})")
        token = api("POST", f"/devices/{device_id}/data-sources/{source_id}/token", {}, actor)["token"]
        reading = {"deviceId": device_id, "sourceId": external, "timestamp": iso(now), "metrics": {"activePowerW": watts, "voltageV": 230, "currentA": round(watts / 230, 3), "temperatureC": 4.5 + index % 4, "energyWh": round(10000 + 14 * 24 * watts * .8, 3)}}
        import urllib.request
        request = urllib.request.Request("http://127.0.0.1:8083/ingestion/imports/readings/batch", data=json.dumps({"readings": [reading]}).encode(), headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        for attempt in range(20):
            with urllib.request.urlopen(request, timeout=30) as response:
                result = json.load(response)
            if not result.get("rejectedCount"):
                break
            time.sleep(2)
        if result.get("rejectedCount"):
            raise RuntimeError("Demo latest reading was rejected: " + str([item.get("reason") for item in result.get("items", [])]))
        print(f"Prepared {index + 1}/{len(devices)}: {device['name']}", flush=True)
    sql = f"""
INSERT INTO telemetry.measurements(account_id,device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
SELECT '{account}'::uuid,d.id,d.source,t,m.metric,
CASE m.metric
WHEN 'activePowerW' THEN d.watts*(.8+.16*sin(extract(epoch FROM t)/3600+d.phase))
WHEN 'energyWh' THEN 10000+extract(epoch FROM t-'{iso(start)}'::timestamp)/3600*d.watts*.8
WHEN 'voltageV' THEN 230+2*sin(extract(epoch FROM t)/1800)
WHEN 'temperatureC' THEN 4.5+d.phase%4+.6*sin(extract(epoch FROM t)/7200)
ELSE d.watts/230.0 END,
m.unit,'OK',t,t
FROM (VALUES {','.join(tuples)}) d(id,source,watts,phase)
CROSS JOIN generate_series('{iso(start)}'::timestamp,'{iso(now)}'::timestamp,interval '15 minutes') t
CROSS JOIN (VALUES ('activePowerW','W'),('energyWh','Wh'),('voltageV','V'),('temperatureC','C'),('currentA','A')) m(metric,unit)
WHERE NOT EXISTS (SELECT 1 FROM telemetry.measurements existing WHERE existing.account_id='{account}'::uuid AND existing.device_id=d.id AND existing.source_id=d.source AND existing.recorded_at=t AND existing.metric_name=m.metric);
"""
    subprocess.run(["docker", "compose", "exec", "-T", "timescaledb", "psql", "-U", "voltcanary", "-d", "voltcanary_telemetry", "-v", "ON_ERROR_STOP=1"], input=sql, text=True, check=True, capture_output=True)
    tariffs = api("GET", "/energy-tariffs", actor=actor)
    if not tariffs:
        api("POST", "/energy-tariffs", {"reviewed": True, "tariff": {"version": 0, "accountId": account, "currency": "EUR", "timezone": "Europe/Belgrade", "ratePerKWh": "0.15", "effectiveFrom": iso(start), "effectiveTo": iso(now+timedelta(days=30)), "taxesIncluded": True, "reason": "DEMO synthetic tariff, not a supplier contract", "archived": False}}, actor)
    ids = [d["id"] for d in devices]
    widgets = []
    def panel(id, kind, title, query=None, visualization=None, selected=None, payload=None):
        widget = {"id": id, "type": kind, "typeVersion": 1, "title": title, "binding": {"mode": "FIXED", "deviceIds": selected or ids}, "query": query or {}, "presentation": {"showTitle": True, "showAxes": True}, "navigation": {}, "payload": payload or {}}
        if kind == "time-series":
            widget["query"]["series"] = [{"deviceId": (selected or ids)[0], "metric": widget["query"].pop("metric")}]
            widget["query"]["cadenceSeconds"] = 900
        if visualization:
            widget["presentation"]["visualization"] = visualization
        if kind in ("fleet-count", "quality-and-freshness") and visualization != "number":
            widget["presentation"]["showLegend"] = True
        widgets.append(widget)
        return id
    overview = [panel("energy", "energy-period", "Energy · last 7 days", {"independentMetersConfirmed": True}), panel("cost", "estimated-cost", "Estimated spend · EUR", {"independentMetersConfirmed": True}), panel("power", "latest-value", "Workshop · current power", {"metric": "activePowerW", "unit": "W"}, "number", [ids[0]]), panel("voltage", "latest-value", "Supply · voltage", {"metric": "voltageV", "unit": "V"}, "number", [ids[0]]), panel("load", "time-series", "Power profile · recorded load", {"metric": "activePowerW", "reducer": "AVG", "rows": 50}, "area", [ids[0]]), panel("fleet", "fleet-count", "Equipment · measurement freshness", visualization="donut"), panel("temperature", "time-series", "Cooling · temperature profile", {"metric": "temperatureC", "reducer": "AVG", "rows": 50}, "line", [ids[0]]), panel("note", "context-note", "Demo data · how to read this dashboard", payload={"text": "Synthetic demonstration · 14 days of recorded measurements. Independent demo meters; EUR 0.15/kWh sample tariff. Estimates are not invoices. Use Trends for device detail and Energy for consumption ranking."})]
    trends = [panel("bars", "time-series", "Recorded power · interval average", {"metric": "activePowerW", "reducer": "AVG", "rows": 24}, "bar", [ids[min(1,len(ids)-1)]]), panel("volts", "time-series", "Supply stability · voltage", {"metric": "voltageV", "reducer": "AVG", "rows": 50}, "line", [ids[0]]), panel("pie", "fleet-count", "Equipment freshness · distribution", visualization="pie"), panel("stacked", "quality-and-freshness", "Measurement coverage · categories", visualization="stacked-bar")]
    energy = [panel("ranking", "peak-and-ranking", "Energy & recorded peaks · equipment ranking", {"rows": 20, "independentMetersConfirmed": True}), panel("energy-detail", "energy-period", "Verified consumption · meter coverage", {"independentMetersConfirmed": True})]
    widgets[-1]["presentation"].update({"showLegend": True, "showCalculation": True})
    layouts = {bp: [] for bp in ("desktop", "tablet", "mobile")}
    positions = [(0,0,3,3),(3,0,3,3),(6,0,3,3),(9,0,3,3),(0,3,8,7),(8,3,4,7),(0,10,8,6),(8,10,4,6)]
    for screen, grid in [(overview, positions), (trends, [(0,0,8,7),(0,7,8,7),(8,0,4,7),(8,7,4,7)]), (energy, [(0,0,8,18),(8,0,4,8)])]:
        for i, id in enumerate(screen):
            x,y,w,h = grid[i]
            layouts["desktop"].append({"widgetId": id,"x": x,"y": y,"w": w,"h": h})
            layouts["tablet"].append({"widgetId": id,"x": 0,"y": i*7,"w": 6,"h": 7})
            mobile_y = sum(3 if previous in ("energy","cost","power","voltage") else 7 for previous in screen[:i])
            layouts["mobile"].append({"widgetId": id,"x": 0,"y": mobile_y,"w": 1,"h": 3 if id in ("energy","cost","power","voltage") else 7})
    definition = {"schemaVersion": 1,"name": "Harbor · Operations & Energy","description": "Synthetic equipment observatory · consumption, cost and recorded trends","defaultContext": {"time": {"mode": "FIXED","timezone": "Europe/Belgrade","from": iso(now-timedelta(days=7)),"to": iso(now)}},"sections": [],"widgets": widgets,"layouts": layouts,"screens": [{"id": id,"title": title,"widgetIds": screen,"grid": {"desktop": {"columns": 12,"rowHeight": 32,"gap": 16},"tablet": {"columns": 6,"rowHeight": 32,"gap": 12},"mobile": {"columns": 1,"rowHeight": 32,"gap": 12}}} for id,title,screen in [("overview","Overview",overview),("trends","Trends",trends),("energy","Energy",energy)]]}
    dashboard = api("POST", "/dashboards", {"definition": definition}, actor, 201, key())["id"]
    draft = api("GET", f"/dashboards/{dashboard}/draft", actor=actor)
    publish(actor, dashboard, draft)
    preferences = api("GET", "/dashboard-preferences/me", actor=actor)
    api("PUT", "/dashboard-preferences/me", {"defaultDashboardId": dashboard,"favorites": list(dict.fromkeys(preferences["favorites"]+[dashboard])),"defaultContext": {}}, actor, headers={"If-Match": '"'+preferences["etag"]+'"'})
    summary = {"email": args.email,"dashboardId": dashboard,"devices": len(devices),"historyDays": 14,"historicalMeasurementsPlanned": len(devices)*(14*96+1)*5,"synthetic": True,"url": f"http://localhost:3000/dashboards/{dashboard}"}
    Path("artifacts").mkdir(exist_ok=True)
    Path("artifacts/demo-dashboard-summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
