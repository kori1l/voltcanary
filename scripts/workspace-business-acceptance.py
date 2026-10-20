"""Additional local acceptance gates, using isolated reserved fixture accounts."""
import json
import runpy
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlencode

helpers = runpy.run_path(str(Path(__file__).with_name("workspace-dashboard-smoke.py")))
api, session, key, publish = (helpers[name] for name in ("api", "session", "key", "publish"))


def main():
    owner, manager, outsider = session(), session(), session()
    workspace = api("POST", "/workspaces", {"name": "DEMO final lifecycle acceptance"}, owner)["id"]
    api("PUT", f"/workspaces/{workspace}/members", {"userId": manager["id"], "role": "MANAGER", "reviewed": True}, owner)
    api("DELETE", f"/workspaces/{workspace}/members/{owner['id']}", actor=owner, expected=409)
    device = api("POST", "/devices", {"name": "DEMO confirmed business evidence", "type": "FRIDGE"}, owner)["id"]
    source = api("POST", f"/devices/{device}/data-sources", {"sourceType": "SMART_PLUG", "protocol": "REST", "externalId": "DEMO-final-acceptance"}, owner)["id"]
    account = owner["account"]
    api("POST", f"/workspaces/{workspace}/resource-accounts", {"accountId": account, "reviewed": True, "active": True}, owner)
    for action in ("device.read", "telemetry.read"):
        api("POST", f"/workspaces/{workspace}/resource-grants", {"reviewed": True, "grant": {"accountId": account, "subjectId": manager["id"], "subjectKind": "USER", "action": action, "scopeKind": "DEVICE", "resourceId": device}}, owner)
    api("GET", f"/energy-tariffs?accountId={account}&workspaceId={workspace}", actor=manager, expected=403)
    now = datetime.now(timezone.utc)
    tariff = {"version": 0, "accountId": account, "currency": "EUR", "timezone": "UTC", "ratePerKWh": "0.125", "effectiveFrom": (now-timedelta(days=1)).isoformat(), "effectiveTo": (now+timedelta(days=1)).isoformat(), "taxesIncluded": False, "reason": "DEMO acceptance", "archived": False}
    saved = api("POST", "/energy-tariffs", {"tariff": tariff, "reviewed": True}, owner)
    revision = api("POST", "/energy-tariffs/revisions", {"tariff": {**saved, "ratePerKWh": "0.175", "reason": "Explicit historical correction"}, "reviewed": True}, owner)
    assert revision["version"] == 2
    api("POST", "/energy-tariffs/revisions", {"tariff": {**saved, "reason": "Stale revision"}, "reviewed": True}, owner, 409)
    api("POST", f"/energy-tariffs/{saved['tariffId']}/archive", actor=owner)
    history = api("GET", "/energy-tariffs", actor=owner)
    assert len(history) == 2 and all(t["archived"] for t in history)
    assert history[0]["ratePerKWh"] != history[1]["ratePerKWh"] and all(t.get("archivedAt") for t in history)
    fact = {"version": 0, "deviceId": device, "sourceId": source, "sourceEventId": "atomic-confirmed", "kind": "CYCLE", "completeness": "COMPLETED", "startAt": (now-timedelta(hours=2)).isoformat(), "endAt": (now-timedelta(hours=1)).isoformat(), "reason": "Confirmed operator record"}
    api("POST", "/operational-events/import", {"facts": [fact, {**fact, "sourceEventId": "invalid-source", "sourceId": str(uuid.uuid4())}], "reviewed": True}, owner, 422)
    query = urlencode({"deviceId": device, "from": (now-timedelta(days=1)).isoformat(), "to": now.isoformat()})
    assert api("GET", "/operational-events?" + query, actor=owner) == []
    definition = {"schemaVersion": 1, "name": "DEMO transfer acceptance", "description": "", "defaultContext": {}, "sections": [], "widgets": [], "layouts": {}}
    dashboard = api("POST", "/dashboards", {"definition": definition}, owner, 201, key())["id"]
    publish(owner, dashboard, api("GET", f"/dashboards/{dashboard}/draft", actor=owner))
    api("POST", f"/dashboards/{dashboard}/workspace", {"workspaceId": workspace, "reviewed": True}, owner)
    metadata = api("GET", f"/dashboards/{dashboard}/grants", actor=owner)
    api("PUT", f"/dashboards/{dashboard}/grants", {"audience": [{"subjectId": outsider["id"], "subjectKind": "USER", "capabilities": ["VIEW"]}], "expectedVersion": metadata["version"], "reviewed": True}, owner, 403)
    preview = api("POST", f"/dashboards/{dashboard}/sharing-preview", {"userIds": [manager["id"]]}, owner)
    assert all(not w["resourceAccessGranted"] for r in preview["recipients"] for w in r["widgets"])
    api("POST", f"/dashboards/{dashboard}/transfer", {"userId": manager["id"], "reviewed": True}, owner)
    api("POST", f"/workspaces/{workspace}/transfer", {"userId": manager["id"], "reviewed": True}, owner)
    assert next(m for m in api("GET", f"/workspaces/{workspace}/members", actor=manager) if m["userId"] == owner["id"])["status"] == "REMOVED"
    api("GET", f"/dashboards/{dashboard}", actor=owner, expected=403)
    assert api("GET", f"/dashboards/{dashboard}", actor=manager)["dashboard"]["ownerUserId"] == manager["id"]
    assert api("GET", f"/dashboards/{dashboard}/sharing-audit", actor=manager)
    api("POST", f"/workspaces/{workspace}/resource-accounts", {"accountId": account, "reviewed": True, "active": False}, owner)
    api("POST", f"/workspaces/{workspace}/devices/query", {"accountId": account, "mode": "ALL"}, manager, 403)
    assert api("GET", f"/devices/{device}", actor=owner)["id"] == device
    audit = api("GET", f"/workspaces/{workspace}/audit", actor=manager)
    assert audit
    checks = ["owner departure requires transfer", "tariff.read independent of telemetry.read", "immutable tariff correction, stale version and archived historical revisions", "fact import atomic rollback on foreign source", "foreign audience rejected and preview does not grant resources", "audited dashboard/workspace ownership transfer and departed owner denied", "departed personal owner can withdraw resource consent", "personal device API preserved after workspace departure"]
    result = {"status": "passed", "checks": checks}
    Path("artifacts/final-business-acceptance.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
