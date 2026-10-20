"""Create populated local persona dashboards and genuine synthetic ML lineage.

Run only for an existing @example.invalid account with process-only
DEMO_DASHBOARD_PASSWORD. No model outputs or credentials are invented or saved
in source. Existing dashboards and the user's personal default are preserved.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import runpy
import subprocess
import time
import uuid
from urllib.request import Request, urlopen

helpers = runpy.run_path(str(Path(__file__).with_name("workspace-dashboard-smoke.py")))
api, key, publish = (helpers[n] for n in ("api", "key", "publish"))
iso = lambda t: t.isoformat().replace("+00:00", "Z")


def sql(statement):
    subprocess.run(["docker", "compose", "exec", "-T", "timescaledb", "psql", "-U", "voltcanary", "-d", "voltcanary_telemetry", "-v", "ON_ERROR_STOP=1"], input=statement, text=True, check=True, capture_output=True)


def wait(label, operation, ready, seconds=300):
    deadline = time.monotonic() + seconds
    while True:
        value = operation()
        if isinstance(value, dict) and value.get("status") in ("FAILED", "CANCELED"):
            raise RuntimeError(label + ": " + str(value.get("failureReason", value.get("errorMessage"))))
        if ready(value):
            return value
        if time.monotonic() > deadline:
            raise TimeoutError(label)
        time.sleep(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--email", required=True)
    parser.add_argument("--resume", action="store_true", help="Reuse the last seeded equipment interval")
    args = parser.parse_args()
    if not args.email.endswith("@example.invalid"):
        raise SystemExit("Reserved local demonstration account required")
    actor = {"token": api("POST", "/auth/login", {"email": args.email, "password": os.environ["DEMO_DASHBOARD_PASSWORD"]})["accessToken"]}
    account = api("GET", "/accounts/current", actor=actor)["id"]
    uuid.UUID(account)
    devices = [d for d in api("GET", "/devices?limit=100", actor=actor) if d["name"].startswith("DEMO refrigeration unit")][:6]
    if len(devices) != 6:
        raise RuntimeError("Expected the six existing synthetic refrigeration devices")
    now = datetime.now(timezone.utc).replace(second=0, microsecond=0) - timedelta(minutes=1)
    start = now - timedelta(hours=24)
    checkpoint = Path("artifacts/demo-role-seed-interval.json")
    if args.resume:
        saved = json.loads(checkpoint.read_text(encoding="utf-8"))
        if saved["accountId"] != account:
            raise RuntimeError("Checkpoint belongs to another account")
        now = datetime.fromisoformat(saved["now"].replace("Z", "+00:00"))
        start = now - timedelta(hours=24)
    sources = []
    for device in devices:
        meter = api("GET", f"/devices/{device['id']}/energy-meter", actor=actor)[-1]
        source = next(s for s in api("GET", f"/devices/{device['id']}/data-sources", actor=actor) if s["id"] == meter["sourceId"])
        sources.append(source)
    print("Refreshing six synthetic equipment histories", flush=True)
    tuples = ",".join(f"('{d['id']}'::uuid,'{s['id']}'::text,{i},{[145,230,180,320,125,270][i]})" for i, (d, s) in enumerate(zip(devices, sources)))
    if not args.resume:
        sql(f"""WITH scenario AS (
SELECT d.id,d.source,d.phase,t,
greatest(12,d.watts*(.62+.24*sin(extract(epoch FROM t)/900+d.phase))
+CASE WHEN mod(floor(extract(epoch FROM t)/1500)::integer+d.phase,5)=0 THEN d.watts*1.3 ELSE 0 END
+CASE WHEN d.phase IN(3,5) THEN d.watts*.8*extract(epoch FROM t-'{iso(start)}'::timestamp)/86400 ELSE 0 END
+CASE WHEN mod(floor(extract(epoch FROM t)/120)::integer+d.phase,113)<2 THEN d.watts*3 ELSE 0 END) power,
4+d.phase*.35+sin(extract(epoch FROM t)/2300+d.phase)
+CASE WHEN d.phase IN(3,5) THEN 3*extract(epoch FROM t-'{iso(start)}'::timestamp)/86400 ELSE 0 END temperature,
230+2*sin(extract(epoch FROM t)/450+d.phase)
+CASE WHEN mod(floor(extract(epoch FROM t)/120)::integer,103)<3 THEN -23 ELSE 0 END voltage
FROM (VALUES {tuples}) d(id,source,phase,watts)
CROSS JOIN generate_series('{iso(start)}'::timestamp,'{iso(now)}'::timestamp,interval '2 minutes') t)
INSERT INTO telemetry.measurements(account_id,device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
SELECT '{account}'::uuid,id,source,t,m.metric,m.value,m.unit,'OK',t,t FROM scenario
CROSS JOIN LATERAL (VALUES ('activePowerW',power,'W'),('temperatureC',temperature,'C'),('voltageV',voltage,'V'),('currentA',power/voltage,'A')) m(metric,value,unit)
WHERE NOT EXISTS(SELECT 1 FROM telemetry.measurements e WHERE e.account_id='{account}'::uuid AND e.device_id=id AND e.source_id=source AND e.recorded_at=t AND e.metric_name=m.metric);""")
    for i, (device, source) in enumerate([] if args.resume else zip(devices, sources)):
        token = api("POST", f"/devices/{device['id']}/data-sources/{source['id']}/token", {}, actor)["token"]
        watts = [150,240,180,610,130,520][i]
        reading = {"deviceId": device["id"], "sourceId": source["externalId"], "timestamp": iso(now+timedelta(seconds=30)), "metrics": {"activePowerW": watts, "temperatureC": [4.2,4.6,4.4,8.2,4.1,7.8][i], "voltageV": 229, "currentA": round(watts/229,4)}}
        request = Request("http://127.0.0.1:8083/ingestion/imports/readings/batch", data=json.dumps({"readings": [reading]}).encode(), headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        for attempt in range(20):
            with urlopen(request, timeout=30) as response:
                accepted = json.load(response)
            if not accepted.get("rejectedCount"):
                break
            time.sleep(2)
        if accepted.get("rejectedCount"):
            raise RuntimeError("Synthetic current readings were rejected: " + str([item.get("reason") for item in accepted.get("items", [])]))
    for i, device in enumerate(devices):
        title = "DEMO · " + ("Проверить рост нагрузки компрессора" if i in (3,5) else "Плановая проверка охлаждения")
        windows = [w for w in api("GET", f"/devices/{device['id']}/maintenance-windows?limit=100", actor=actor) if w["title"] == title and not w.get("cancelledAt")]
        if not windows:
            api("POST", f"/devices/{device['id']}/maintenance-windows", {"title": title, "startAt": iso(now+timedelta(hours=i+2)), "endAt": iso(now+timedelta(hours=i+3)), "suppressAlerts": False, "excludeFromAnalytics": False, "excludeFromMl": False}, actor)
        for duplicate in windows[1:]:
            api("POST", f"/devices/{device['id']}/maintenance-windows/{duplicate['id']}/cancel", {"reason":"Duplicate synthetic persona seed window"}, actor)

    checkpoint.write_text(json.dumps({"accountId":account,"now":iso(now)}),encoding="utf-8")
    # A separate explicit synthetic teaching appliance keeps training labels away
    # from the operator's six devices and preserves their operational history.
    teaching = next((d for d in api("GET", "/devices?limit=100", actor=actor) if d["name"] == "DEMO · Учебный холодильник ML"), None)
    if teaching is None:
        teaching = api("POST", "/devices", {"name": "DEMO · Учебный холодильник ML", "type": "FRIDGE", "locationId": devices[0]["locationId"], "metadata": {"synthetic": True, "fixture": "PREDICTIVE_ANALYST_TRAINING"}}, actor)
    training_sources = api("GET", f"/devices/{teaching['id']}/data-sources", actor=actor)
    training_source = next((s for s in training_sources if s["externalId"] == "DEMO-predictive-training"), None) or api("POST", f"/devices/{teaching['id']}/data-sources", {"sourceType": "SMART_PLUG", "protocol": "REST", "externalId": "DEMO-predictive-training"}, actor)
    models = api("GET", "/analytics/ml/models", actor=actor)
    demo_datasets = {d["datasetVersionId"] for d in api("GET", "/analytics/ml/datasets", actor=actor) if d["name"] == "DEMO · Сценарий предиктивного аналитика"}
    model = next((m for m in models if m["datasetVersionId"] in demo_datasets and m["family"] == "FAILURE_PREDICTION" and m["status"] in ("APPROVED", "ACTIVE")), None)
    if model is None:
        print("Materializing a 28-day synthetic failure-prediction training scenario", flush=True)
        training_end = now - timedelta(hours=2)
        training_start = training_end - timedelta(days=28)
        outcome_at = training_start + timedelta(days=21)
        # Only the dedicated synthetic teaching fixture is replaced. Dataset
        # versions already materialized from it remain immutable.
        sql(f"""DELETE FROM telemetry.measurements WHERE account_id='{account}'::uuid AND device_id='{teaching['id']}'::uuid AND source_id='{training_source['id']}';
INSERT INTO telemetry.measurements(account_id,device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
SELECT '{account}'::uuid,'{teaching['id']}'::uuid,'{training_source['id']}',t,'activePowerW',
CASE WHEN t>'{iso(outcome_at-timedelta(days=7))}'::timestamp AND t<'{iso(outcome_at)}'::timestamp
THEN 1030+extract(epoch FROM t-'{iso(training_start)}'::timestamp)/86400*9+24*sin(extract(epoch FROM t)/14400)
ELSE 820+18*sin(extract(epoch FROM t)/43200) END,'W','OK',t,t
FROM generate_series('{iso(training_start)}'::timestamp,'{iso(training_end)}'::timestamp,interval '4 hours') t;""")
        operation = api("POST", "/maintenance-operations", {"title": "DEMO · Учебный отказ компрессора", "description": "Synthetic labeled teaching scenario, not a real failure", "deviceIds": [teaching["id"]], "startAt": iso(outcome_at-timedelta(hours=1)), "endAt": iso(outcome_at+timedelta(hours=2)), "suppressAlerts": False, "excludeFromAnalytics": False, "excludeFromMl": False}, actor)
        api("POST", f"/maintenance-operations/{operation['id']}/outcomes", {"deviceId": teaching["id"], "observedAt": iso(outcome_at), "resolvedAt": iso(outcome_at+timedelta(hours=2)), "outcomeType": "CORRECTIVE_MAINTENANCE", "failureMode": "DEMO-compressor-drift", "component": "compressor", "severity": "HIGH", "downtimeMinutes": 90, "technicianNotes": "Synthetic training outcome", "source": "OPERATOR", "confidence": .95}, actor)
        selection = {"modelFamily": "FAILURE_PREDICTION", "deviceIds": [teaching["id"]], "deviceType": "fridge", "deviceMetadata": {"fixture": "PREDICTIVE_ANALYST_TRAINING"}, "metricSelectionMode": "COMMON", "selectedMetrics": ["activePowerW"], "from": iso(training_start), "to": iso(training_end), "horizonDays": 7, "splitStrategy": "TIME_BASED", "splitConfigJson": '{"train":0.7,"validation":0.15,"test":0.15}', "featureConfigVersion": "demo-predictive-v1", "minSamplesPerMetric": 24, "maxSamplingGapSeconds": 21600, "candidateLimit": 4}
        preview = wait("Projected failure labels", lambda: api("POST", "/analytics/ml/datasets/internal-telemetry/preview", selection, actor), lambda p: p.get("previewHash") and p["status"] != "BLOCKED" and p.get("labelReadiness", {}).get("positiveLabels",0)>0)
        dataset = api("POST", "/analytics/ml/datasets/internal-telemetry", {**{k:v for k,v in selection.items() if k not in ("selectedMetrics","candidateLimit")}, "name": "DEMO · Сценарий предиктивного аналитика", "description": "Synthetic refrigeration training data", "metrics": ["activePowerW"], "previewId": preview["previewId"], "previewHash": preview["previewHash"]}, actor)
        labels = api("POST", "/analytics/ml/datasets/horizon-labels", {"datasetVersionId": dataset["datasetVersionId"], "horizonDays": 7, "generationVersion": "demo-confirmed-synthetic-v1", "includePreventiveMaintenance": False}, actor,202)
        wait("Horizon labels", lambda: api("GET", f"/analytics/ml/datasets/horizon-labels/{labels['jobId']}", actor=actor), lambda j: j["status"] == "COMPLETED")
        features = api("POST", "/analytics/ml/datasets/feature-snapshots", {"datasetVersionId": dataset["datasetVersionId"], "featureSetVersion": "feature-set-v1", "codeConfigVersion": "analytics-feature-pipeline-v1", "rollingWindowSize": 6, "missingGapSeconds": 21600, "enabledFeatureFamilies": ["rolling_stats","baseline_deviation","missing_data","time","device_context","label"], "modelFamily": "FAILURE_PREDICTION", "horizonDays": 7, "featureTemplateId": "generic-appliance", "featureTemplateVersion": "template-v1"}, actor)
        training = api("POST", "/analytics/ml/training-runs", {"datasetVersionId": dataset["datasetVersionId"], "featureSnapshotId": features["id"], "modelFamily": "FAILURE_PREDICTION", "algorithm": None, "failureHorizonDays": 7, "minimumPositiveLabels": 1, "minimumLabelCoverage": .01, "approvalGateProfile": {"profileId": "demo-synthetic-fridge", "profileVersion": "2026-10", "thresholdSource": "DEMO_SYNTHETIC_TRAINING", "minimumPositiveLabels": 1, "minimumLabelCoverage": .01, "minimumPrecision": .6, "minimumRecall": .6, "minimumPrAuc": .6, "minimumPrAucLift": 0, "maximumFalseAlertsPerDeviceMonth": 10, "minimumLeadTimeDays": 0, "requireArtifactLoadTest": True, "requireFeatureSchemaCompatibility": True}, "hyperparametersJson": '{"synthetic":true,"purpose":"demo-analyst"}'}, actor)
        completed = wait("Training", lambda: next(r for r in api("GET", "/analytics/ml/training-runs", actor=actor) if r["id"] == training["id"]), lambda r:r["status"] == "SUCCEEDED")
        model = wait("Model registration", lambda: next((m for m in api("GET", "/analytics/ml/models", actor=actor) if m["featureSnapshotId"] == features["id"]), None), lambda m:m is not None)
        model = api("POST", f"/analytics/ml/models/{model['id']}/approve", {"reason": "DEMO synthetic scenario; artifact and approval gates verified, not real failure validation"}, actor)
        print("Trained model approved after its configured gates", flush=True)
    predictions = []
    policies = api("GET", "/analytics/ml/deployment-policies", actor=actor)
    for device in devices:
        if not any(p.get("deviceId") == device["id"] and p.get("mode") == "ACTIVE" and p.get("modelVersionId") == model["id"] for p in policies):
            api("POST", "/analytics/ml/deployment-policies", {"scope": "DEVICE", "deviceId": device["id"], "modelVersionId": model["id"], "mode": "ACTIVE", "fallbackEnabled": True, "reason": "DEMO synthetic predictive analyst walkthrough"}, actor)
        api("PUT", f"/analytics/ml-settings/devices/{device['id']}", {"mlEnabled": True, "predictionMode": "MODEL_OR_BASELINE_HEURISTIC", "confidenceWarningThreshold": .6, "maintenanceBehavior": "REDUCE_CONFIDENCE", "explanationsVisible": True}, actor)
        job = api("POST", f"/analytics/devices/{device['id']}/recalculate", actor=actor)
        wait("Device recalculation", lambda: api("GET", f"/analytics/devices/{device['id']}/jobs/{job['jobId']}", actor=actor), lambda j:j["status"] in ("COMPLETED", "SKIPPED"))
        prediction = api("GET", f"/analytics/devices/{device['id']}/predictions/latest", actor=actor)
        if prediction["predictionStatus"] != "TRAINED_MODEL":
            raise RuntimeError("Expected trained model evidence, got " + prediction["predictionStatus"] + ": " + str(prediction.get("fallbackReason")))
        predictions.append({"deviceId":device["id"], "name":device["name"], "predictionStatus":prediction["predictionStatus"], "failureRiskScore":prediction.get("failureRiskScore"), "failureHorizonDays":prediction.get("failureHorizonDays"), "confidence":prediction.get("confidence"), "modelVersionId":prediction.get("modelVersionId")})
        print(device["name"] + ": persisted trained prediction", flush=True)

    ids = [d["id"] for d in devices]
    class Dashboard:
        def __init__(self, name, description):
            self.name,self.description,self.widgets,self.layout = name,description,[],[]
        def add(self, id, kind, title, x,y,w,h, query=None, visual=None, selected=None, text=None):
            presentation = {"showTitle":True,"showAxes":True}
            if kind=="risk-list": presentation["showRecordTime"]=False
            if kind=="maintenance-agenda": presentation["showMaintenanceFlags"]=False
            if kind=="prediction-evidence": presentation.update({"predictionSort":"RISK_DESC","showPredictionModel":False,"showPredictionTime":False})
            if visual: presentation["visualization"]=visual
            if visual in ("line","area","donut","horizontal-bar"): presentation["showLegend"]=True
            self.widgets.append({"id":id,"type":kind,"typeVersion":1,"title":title,"binding":{"mode":"FIXED","deviceIds":selected or ids},"query":query or {},"presentation":presentation,"navigation":{"href":{"prediction-evidence":"/settings/ml","incident-list":"/alerts","maintenance-agenda":"/maintenance"}.get(kind,"/devices")},"payload":{"text":text} if text else {}})
            self.layout.append({"widgetId":id,"x":x,"y":y,"w":w,"h":h})
        def series(self,id,title,metric,x,y,w,h,visual="line",selected=None):
            self.add(id,"time-series",title,x,y,w,h,{"series":[{"deviceId":d,"metric":metric} for d in (selected or ids)],"reducer":"AVG","rows":50},visual,selected)
        def finish(self):
            layouts={"desktop":self.layout,"tablet":[],"mobile":[]}
            for bp,columns in (("tablet",6),("mobile",1)):
                y=0
                for p in self.layout:layouts[bp].append({**p,"x":0,"y":y,"w":columns});y+=p["h"]
            definition={"schemaVersion":1,"name":self.name,"description":self.description,"defaultContext":{"time":{"mode":"FIXED","timezone":"Europe/Belgrade","from":iso(start),"to":iso(now)}},"widgets":self.widgets,"sections":[],"layouts":layouts,"screens":[{"id":"overview","title":"Рабочий обзор","widgetIds":[w["id"] for w in self.widgets],"grid":{"desktop":{"columns":12,"rowHeight":28,"gap":12},"tablet":{"columns":6,"rowHeight":28,"gap":10},"mobile":{"columns":1,"rowHeight":40,"gap":10}}}]}
            catalog=api("GET","/dashboards?limit=100",actor=actor)["items"]
            existing=next((d for d in catalog if d["name"]==self.name),None)
            if existing:
                id=existing["id"];draft=api("GET",f"/dashboards/{id}/draft",actor=actor)
                draft=api("PUT",f"/dashboards/{id}/draft",{"definition":definition,"baseRevision":draft["baseRevision"]},actor,headers={"If-Match":'"'+draft["etag"]+'"'})
            else:
                id=api("POST","/dashboards",{"definition":definition},actor,201,key())["id"];draft=api("GET",f"/dashboards/{id}/draft",actor=actor)
            validation=api("POST",f"/dashboards/{id}/query-validation",{"draftEtag":draft["etag"]},actor)
            if any(p["status"] not in ("valid","ready") for p in validation["panels"]):raise RuntimeError("Dashboard definition validation failed")
            publish(actor,id,draft)
            evidence=api("POST",f"/dashboards/{id}/query",{"forceRefresh":True},actor)
            if any(p["status"]!="ready" for p in evidence["panels"]):raise RuntimeError(self.name+": "+json.dumps([(p["widgetId"],p["status"],p.get("explanation")) for p in evidence["panels"]]))
            Path(f"artifacts/demo-role-{id}.json").write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding="utf-8")
            return {"id":id,"name":self.name,"widgets":len(self.widgets),"url":"http://localhost:3000/dashboards/"+id}

    analyst=Dashboard("Harbor · Предиктивная аналитика","DEMO · Аналитик · обученная модель, сохранённые прогнозы и сигналы оборудования")
    analyst.add("risk","risk-list","Приоритет проверки · рейтинг риска",0,0,5,8,{"rows":6})
    analyst.add("prediction","prediction-evidence","Прогнозы обученной модели · горизонт 7 дней",5,0,7,8,{"rows":6},selected=ids)
    analyst.series("load","Нагрузка · сравнение шести устройств","activePowerW",0,8,8,6,"area")
    analyst.add("quality","quality-and-freshness","Пригодность данных · свежесть",8,8,4,6,visual="donut")
    analyst.series("temperature","Температура · дрейф охлаждения","temperatureC",0,14,6,5)
    analyst.series("voltage","Напряжение · независимый сигнал","voltageV",6,14,6,5)
    analyst.add("incidents","incident-list","Сохранённые инциденты · что проверить",0,19,7,6,{"incidentMode":"ACTIVE","rows":6})
    analyst.add("method","context-note","Как читать прогноз и риск",7,19,5,6,text="DEMO: синтетические холодильники. FAILURE_PREDICTION обучена на отдельном учебном устройстве; прогнозы рассчитаны через ML и сохранены. Горизонт — 7 дней. Risk score — рейтинг правил, не вероятность отказа. Confidence — оценка модели, не измеренная точность. Сначала проверьте дрейф, затем откройте устройство и модель в ML workspace.")
    analyst.add("peak","metric-summary","Пик нагрузки · W",0,25,3,3,{"metric":"activePowerW","reducer":"MAX","unit":"W"})
    analyst.add("p95","metric-summary","95-й перцентиль · W",3,25,3,3,{"metric":"activePowerW","reducer":"P95","unit":"W"})
    analyst.add("temp","metric-summary","Температурный максимум · °C",6,25,3,3,{"metric":"temperatureC","reducer":"MAX","unit":"C"})
    analyst.add("energy","energy-period","Энергия · проверка нагрузки",9,25,3,3,{"independentMetersConfirmed":True})

    service=Dashboard("Harbor · Сервис и риски","DEMO · Техник · приоритет оборудования, инциденты и обслуживание")
    service.add("risk","risk-list","Очередь диагностики · риск",0,0,6,8,{"rows":6})
    service.add("agenda","maintenance-agenda","План обслуживания · ближайшие 7 дней",6,0,6,8,{"rows":6,"time":{"mode":"RELATIVE","preset":"NEXT_7_DAYS","timezone":"Europe/Belgrade"}})
    service.add("incidents","incident-list","Открытые инциденты · рабочая очередь",0,8,8,7,{"incidentMode":"ACTIVE","rows":8})
    service.add("quality","quality-and-freshness","Доступность измерений",8,8,4,7,visual="donut")
    service.series("load","Компрессоры 4 и 6 · рост нагрузки","activePowerW",0,15,6,6,"area",[ids[3],ids[5]])
    service.series("temperature","Температуры 4 и 6 · проверка охлаждения","temperatureC",6,15,6,6,"line",[ids[3],ids[5]])
    for i,d in enumerate((devices[3],devices[5])):
        service.add("current"+str(i),"latest-value","Устройство "+str(4+i*2)+" · текущая мощность",i*4,21,4,3,{"metric":"activePowerW","unit":"W"},selected=[d["id"]])
    service.add("note","context-note","Демо-процедура техника",8,21,4,3,text="Сначала инцидент и условия измерения, затем устройство и план обслуживания. Прогноз — основание для проверки; синтетический сценарий не доказывает реальный отказ.")

    energy=Dashboard("Harbor · Энергоаналитика","DEMO · Аналитик затрат · нагрузка, потребление и тарифная оценка")
    energy.add("total","energy-period","Энергия за сутки · kWh",0,0,3,3,{"independentMetersConfirmed":True})
    energy.add("cost","estimated-cost","Стоимость · оценка EUR",3,0,3,3,{"independentMetersConfirmed":True})
    energy.add("peak","metric-summary","Максимум мощности · W",6,0,3,3,{"metric":"activePowerW","reducer":"MAX","unit":"W"})
    energy.add("mean","metric-summary","Средняя мощность · W",9,0,3,3,{"metric":"activePowerW","reducer":"MEAN","unit":"W"})
    energy.series("load","Профиль потребления · шесть устройств","activePowerW",0,3,8,7,"area")
    energy.add("donut","energy-period","Доля оборудования в потреблении",8,3,4,7,{"independentMetersConfirmed":True},"donut")
    energy.add("ranking","peak-and-ranking","Рейтинг потребления и пиков",0,10,7,8,{"independentMetersConfirmed":True,"rows":6})
    energy.add("bars","energy-period","Потребление по оборудованию · kWh",7,10,5,8,{"independentMetersConfirmed":True},"horizontal-bar")
    energy.series("voltage","Качество питания · просадки","voltageV",0,18,6,5)
    energy.series("temperature","Температура и режим работы","temperatureC",6,18,6,5)
    energy.add("note","context-note","Границы расчёта",0,23,12,3,text="DEMO · Энергия интегрируется по полным измерениям зарегистрированных независимых счётчиков. Сравните рост нагрузки устройств 4 и 6 с температурой. EUR 0.15/kWh — демонстрационный тариф; стоимость является оценкой, не счётом. Пропуски и неполное покрытие раскрываются в информации панели.")
    dashboards=[d.finish() for d in (analyst,service,energy)]
    preferences=api("GET","/dashboard-preferences/me",actor=actor)
    api("PUT","/dashboard-preferences/me",{"defaultDashboardId":preferences["defaultDashboardId"],"favorites":list(dict.fromkeys(preferences["favorites"]+[d["id"] for d in dashboards])),"defaultContext":preferences["defaultContext"]},actor,headers={"If-Match":'"'+preferences["etag"]+'"'})
    summary={"synthetic":True,"accountId":account,"modelVersionId":model["id"],"predictions":predictions,"dashboards":dashboards}
    Path("artifacts/demo-role-dashboards-summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(summary,ensure_ascii=False,indent=2))


if __name__=="__main__":
    main()
