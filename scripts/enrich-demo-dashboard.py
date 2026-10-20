"""Local synthetic stress scenario for an existing demo dashboard; credentials are process-only."""
import argparse
from datetime import datetime,timedelta,timezone
import json
import os
from pathlib import Path
import runpy
import subprocess
import uuid

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--email",required=True)
    parser.add_argument("--dashboard",required=True)
    args=parser.parse_args()
    if not args.email.endswith("@example.invalid"):raise SystemExit("Reserved local demo account required")
    uuid.UUID(args.dashboard)
    helpers=runpy.run_path(str(Path(__file__).with_name("workspace-dashboard-smoke.py")))
    api=helpers["api"]
    actor={"token":api("POST","/auth/login",{"email":args.email,"password":os.environ["DEMO_DASHBOARD_PASSWORD"]})["accessToken"]}
    account=api("GET","/accounts/current",actor=actor)["id"]
    devices=api("GET","/devices?limit=100",actor=actor)[:6]
    now=datetime.now(timezone.utc).replace(second=0,microsecond=0)-timedelta(minutes=1)
    start=now-timedelta(hours=24)
    iso=lambda t:t.isoformat().replace("+00:00","Z")
    tuples=[]
    for i,device in enumerate(devices):
        id=device["id"];external="DEMO-rich-"+id
        sources=api("GET",f"/devices/{id}/data-sources",actor=actor)
        source=next((s for s in sources if s.get("externalId")==external),None) or api("POST",f"/devices/{id}/data-sources",{"sourceType":"SMART_PLUG","protocol":"REST","externalId":external},actor)
        history=api("GET",f"/devices/{id}/energy-meter",actor=actor)
        latest=history[-1]
        if latest["sourceId"]!=source["id"]:
            api("PUT",f"/devices/{id}/energy-meter",{"expectedRevision":latest["revision"],"physicalMeterId":latest["physicalMeterId"],"parentPhysicalMeterId":latest.get("parentPhysicalMeterId"),"sourceId":source["id"],"authoritativeMethod":"POWER_INTEGRAL","maxGapSeconds":300,"effectiveFrom":iso(start),"counterEvents":[]},actor)
        tuples.append(f"('{id}'::uuid,'{source['id']}'::text,{[145,230,180,320,125,270][i]},{i})")
        facts=[{"version":0,"deviceId":id,"sourceId":source["id"],"sourceEventId":"rich-"+now.strftime('%Y%m%d%H%M')+"-observation","kind":"OBSERVATION","completeness":"COMPLETED","startAt":iso(start),"endAt":iso(now),"reason":"DEMO synthetic confirmed observation"}]
        for n in range(8):
            begin=start+timedelta(hours=n*3+.5+i*.05)
            facts.append({"version":0,"deviceId":id,"sourceId":source["id"],"sourceEventId":f"rich-{now:%Y%m%d%H%M}-cycle-{n}","kind":"CYCLE","completeness":"COMPLETED","startAt":iso(begin),"endAt":iso(begin+timedelta(minutes=45+i*3)),"reason":"DEMO synthetic confirmed compressor cycle"})
        facts.append({"version":0,"deviceId":id,"sourceId":source["id"],"sourceEventId":f"rich-{now:%Y%m%d%H%M}-downtime","kind":"DOWNTIME","completeness":"COMPLETED","startAt":iso(start+timedelta(hours=14+i*.2)),"endAt":iso(start+timedelta(hours=14.5+i*.3)),"reason":"DEMO synthetic recorded cooling interruption"})
        facts.append({"version":0,"deviceId":id,"sourceId":source["id"],"sourceEventId":f"rich-{now:%Y%m%d%H%M}-late-stop","kind":"DOWNTIME","completeness":"COMPLETED","startAt":iso(now-timedelta(hours=2)) ,"endAt":iso(now-timedelta(hours=2)+timedelta(minutes=20+i*3)),"reason":"DEMO synthetic confirmed late compressor stop"})
        api("POST","/operational-events/import",{"accountId":account,"facts":facts,"reviewed":True},actor)
        api("POST",f"/devices/{id}/maintenance-windows",{"title":"DEMO · Compressor inspection","startAt":iso(now+timedelta(hours=2+i)),"endAt":iso(now+timedelta(hours=3+i)),"suppressAlerts":False,"excludeFromAnalytics":False,"excludeFromMl":False},actor)
    # Canonical telemetry metrics and a registered synthetic meter source.
    sql=f"""WITH scenario AS (
SELECT '{account}'::uuid account,d.id,d.source,d.phase,t,
greatest(10,d.watts*(.45+.35*sin(extract(epoch FROM t)/650+d.phase)) + CASE WHEN mod(floor(extract(epoch FROM t)/1200)::integer+d.phase,5)=0 THEN d.watts*1.9 ELSE 0 END + CASE WHEN mod(floor(extract(epoch FROM t)/120)::integer+d.phase,97)<3 THEN d.watts*3.2 ELSE 0 END) power,
4.0+d.phase*.35+1.4*sin(extract(epoch FROM t)/2300+d.phase)+CASE WHEN mod(floor(extract(epoch FROM t)/3600)::integer+d.phase,7)=0 THEN 4.5 ELSE 0 END temperature,
230+3*sin(extract(epoch FROM t)/350+d.phase)+CASE WHEN mod(floor(extract(epoch FROM t)/120)::integer,89)<4 THEN -22 ELSE 0 END voltage
FROM (VALUES {','.join(tuples)}) d(id,source,watts,phase)
CROSS JOIN generate_series('{iso(start)}'::timestamp,'{iso(now)}'::timestamp,interval '2 minutes') t)
INSERT INTO telemetry.measurements(account_id,device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
SELECT account,id,source,t,m.metric,m.value,m.unit,'OK',t,t FROM scenario
CROSS JOIN LATERAL (VALUES ('activePowerW',power,'W'),('temperatureC',temperature,'C'),('voltageV',voltage,'V'),('currentA',power/230,'A')) m(metric,value,unit);"""
    subprocess.run(["docker","compose","exec","-T","timescaledb","psql","-U","voltcanary","-d","voltcanary_telemetry","-v","ON_ERROR_STOP=1"],input=sql,text=True,check=True,capture_output=True)
    ids=[d["id"] for d in devices]
    widgets=[];positions=[]
    def add(id,type,title,x,y,w,h,query=None,visual=None,selection=None,payload=None):
        q=query or {}
        if type=="time-series":
            metric=q.pop("metric")
            q["series"]=[{"deviceId":did,"metric":metric} for did in (selection or ids)]
        presentation={"showTitle":True,"showAxes":True}
        if visual:presentation["visualization"]=visual
        if type in ("time-series","energy-period") and visual not in (None,"number"):presentation["showLegend"]=True
        if type=="time-series" and selection and len(selection)==1:presentation["showLegend"]=False
        widgets.append({"id":id,"type":type,"typeVersion":1,"title":title,"binding":{"mode":"FIXED","deviceIds":selection or ids},"query":q,"presentation":presentation,"navigation":{},"payload":payload or {}})
        positions.append({"widgetId":id,"x":x,"y":y,"w":w,"h":h})
    add("energy","energy-period","Энергия за 24 часа",0,0,2,2,{"independentMetersConfirmed":True})
    add("cost","estimated-cost","Расходы · оценка EUR",2,0,2,2,{"independentMetersConfirmed":True})
    add("peak","metric-summary","Максимум мощности · W",4,0,2,2,{"metric":"activePowerW","reducer":"MAX","unit":"W"})
    add("average","metric-summary","Средняя нагрузка · W",6,0,2,2,{"metric":"activePowerW","reducer":"MEAN","unit":"W"})
    add("hot","metric-summary","Максимум температуры · °C",8,0,2,2,{"metric":"temperatureC","reducer":"MAX","unit":"C"})
    add("dip","metric-summary","Минимум напряжения · V",10,0,2,2,{"metric":"voltageV","reducer":"MIN","unit":"V"})
    add("power-comparison","time-series","Нагрузка 6 устройств · пики и рабочие циклы",0,2,8,6,{"metric":"activePowerW","reducer":"AVG"},"area")
    add("consumption","energy-period","Кто потребляет энергию · kWh",8,2,4,6,{"independentMetersConfirmed":True},"donut")
    add("temperatures","time-series","Температура · перегрев и восстановление",0,8,6,5,{"metric":"temperatureC","reducer":"AVG"},"line")
    add("voltages","time-series","Напряжение · кратковременные просадки",6,8,6,5,{"metric":"voltageV","reducer":"MIN"},"line")
    add("operations","uptime-or-cycle","Подтверждённые циклы и простой · 6ч",0,13,4,5,{"independentMetersConfirmed":True})
    add("bars","time-series","Устройство 4 · импульсы нагрузки",4,13,4,5,{"metric":"activePowerW","reducer":"MAX"},"bar",[ids[3]])
    add("energy-bars","energy-period","Потребление по оборудованию",8,13,4,5,{"independentMetersConfirmed":True},"horizontal-bar")
    add("current","latest-value","Текущая нагрузка · устройство 4",0,18,3,2,{"metric":"activePowerW","unit":"W"},"number",[ids[3]])
    add("temp-current","latest-value","Температура · устройство 2",3,18,3,2,{"metric":"temperatureC","unit":"C"},"number",[ids[1]])
    add("current-voltage","latest-value","Напряжение · устройство 1",6,18,3,2,{"metric":"voltageV","unit":"V"},"number",[ids[0]])
    add("freshness","quality-and-freshness","Свежесть измерений · 6 устройств",9,18,3,2,visual="stacked-bar")
    add("note","context-note","Демо-сценарий · что происходит",0,20,12,2,payload={"text":"Синтетические данные: нагрузка компрессоров меняется циклами; короткие пики достигают 1–2 кВт, температура поднимается при остановках, напряжение иногда проседает до 205 V. Цвет — отдельное устройство. Период: 24 часа. Энергия рассчитана по зарегистрированным независимым счётчикам; стоимость — оценка по демо-тарифу, не счёт."})
    next(w for w in widgets if w["id"]=="operations")["presentation"]["compactOperations"]=True
    next(w for w in widgets if w["id"]=="operations")["query"]["time"]={"mode":"FIXED","timezone":"Europe/Belgrade","from":iso(now-timedelta(hours=6)),"to":iso(now)}
    for p in positions:
        if p["y"]==0:p["h"]=3
        else:p["y"]+=1
    layouts={"desktop":positions,"tablet":[],"mobile":[]}
    for bp,cols in [("tablet",6),("mobile",1)]:
        y=0
        for p in positions:
            layouts[bp].append({**p,"x":0,"y":y,"w":cols});y+=p["h"]
    definition={"schemaVersion":1,"name":"Harbor · Мониторинг оборудования","description":"Синтетический стресс-сценарий · 6 устройств · 24 часа","defaultContext":{"time":{"mode":"FIXED","timezone":"Europe/Belgrade","from":iso(start),"to":iso(now)}},"sections":[],"widgets":widgets,"layouts":layouts,"screens":[{"id":"overview","title":"Мониторинг · 24 часа","widgetIds":[w["id"] for w in widgets],"grid":{"desktop":{"columns":12,"rowHeight":28,"gap":12},"tablet":{"columns":6,"rowHeight":28,"gap":10},"mobile":{"columns":1,"rowHeight":40,"gap":10}}}]}
    draft=api("GET",f"/dashboards/{args.dashboard}/draft",actor=actor)
    draft=api("PUT",f"/dashboards/{args.dashboard}/draft",{"definition":definition,"baseRevision":draft["baseRevision"]},actor,headers={"If-Match":'"'+draft["etag"]+'"'})
    helpers["publish"](actor,args.dashboard,draft)
    result=api("POST",f"/dashboards/{args.dashboard}/query",{"forceRefresh":True},actor)
    Path("artifacts/demo-rich-dashboard-evidence.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({"widgets":len(widgets),"measurements":len(devices)*721*4,"panels":[{"id":p["widgetId"],"status":p["status"],"error":p.get("explanation")} for p in result["panels"]]},ensure_ascii=False,indent=2))

if __name__=="__main__":main()
