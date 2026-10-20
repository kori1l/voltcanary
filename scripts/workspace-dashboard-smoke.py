"""Local disposable fixtures proving live membership, scope and publication isolation."""
import json
import secrets
import uuid
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = "http://127.0.0.1:8080"
checks = []


def api(method, path, body=None, actor=None, expected=200, headers=None):
    values = {"Content-Type": "application/json", **(headers or {})}
    if actor:
        values["Authorization"] = "Bearer " + actor["token"]
    request = Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=values, method=method)
    try:
        response = urlopen(request, timeout=30)
    except HTTPError as error:
        response = error
    with response:
        status = response.status if hasattr(response, "status") else response.code
        raw = response.read()
    if status != expected:
        raise AssertionError(f"{method} {path}: {status}, expected {expected}: {raw[:400].decode()}")
    return json.loads(raw) if raw else None


def session():
    credentials = {"email": "workspace-smoke-" + uuid.uuid4().hex + "@example.invalid", "password": "Vc9!" + secrets.token_hex(16)}
    api("POST", "/auth/register", credentials)
    token = api("POST", "/auth/login", credentials)["accessToken"]
    actor = {**credentials, "token": token}
    actor["id"] = api("GET", "/auth/me", actor=actor)["id"]
    actor["account"] = api("GET", "/accounts/current", actor=actor)["id"]
    return actor


def key():
    return {"Idempotency-Key": str(uuid.uuid4())}


def publish(actor, dashboard, draft, expected=200):
    return api("POST", f"/dashboards/{dashboard}/publish", {"draftEtag": draft["etag"]}, actor, expected,
               {**key(), "If-Match": '"' + draft["etag"] + '"'})


def main():
    deadline=time.monotonic()+90
    while True:
        try:
            for port in (8080,8081,8082,8084,8085,8086,8089):
                with urlopen(f"http://127.0.0.1:{port}/actuator/health",timeout=2) as health:
                    assert health.status==200
            api("POST","/auth/login",{},expected=400)
            break
        except Exception:
            if time.monotonic()>=deadline:
                raise RuntimeError("Required API services did not become ready")
            time.sleep(2)
    owner, viewer, other = session(), session(), session()
    workspace = api("POST", "/workspaces", {"name": "DEMO reviewed collaboration smoke"}, owner)["id"]
    foreign = api("POST", "/workspaces", {"name": "DEMO foreign workspace"}, other)["id"]
    for actor in (viewer, other):
        api("PUT", f"/workspaces/{workspace}/members", {"userId": actor["id"], "role": "MEMBER", "reviewed": True}, owner)
    team = api("POST", f"/workspaces/{workspace}/teams", {"name": "Reviewed viewer subset", "userIds": [viewer["id"]], "reviewed": True}, owner)["id"]
    location = api("POST", "/locations", {"name": "DEMO workspace location"}, owner)["id"]
    devices = [api("POST", "/devices", {"name": "DEMO scoped equipment " + str(index), "type": "FRIDGE", "locationId": location}, owner)["id"] for index in range(2)]
    account = owner["account"]
    api("POST", f"/workspaces/{workspace}/resource-accounts", {"accountId": account, "reviewed": True, "active": True}, viewer, 403)
    api("POST", f"/workspaces/{workspace}/resource-accounts", {"accountId": account, "reviewed": True, "active": True}, owner)

    def grant(subject, kind, action, device):
        api("POST", f"/workspaces/{workspace}/resource-grants", {"reviewed": True, "grant": {"accountId": account, "subjectId": subject, "subjectKind": kind, "action": action, "scopeKind": "DEVICE", "resourceId": device}}, owner)

    for device in devices:
        for action in ("device.read", "telemetry.read", "maintenance.read", "maintenance.write", "tariff.read", "tariff.write", "operational.read", "operational.write"):
            grant(owner["id"], "USER", action, device)
    for action in ("device.read", "telemetry.read", "maintenance.read"):
        grant(team, "TEAM", action, devices[0])
    scope = {"mode": "ALL", "accountId": account}
    visible = api("POST", f"/workspaces/{workspace}/devices/query", scope, viewer)
    assert [item["id"] for item in visible["devices"]] == [devices[0]]
    assert visible["requestedCount"] == visible["evaluatedCount"] == 1
    assert devices[1] not in json.dumps(visible)
    api("POST", f"/workspaces/{foreign}/devices/query", scope, viewer, 403)
    spoofed = api("POST", f"/workspaces/{workspace}/devices/query", scope, other, headers={"X-User-Id": owner["id"], "X-Workspace-Id": workspace, "X-Account-Id": account})
    assert spoofed["evaluatedCount"] == 0
    checks.append("live team subset, hidden IDs/counts, foreign workspace and header spoof isolation")
    start = datetime.now(timezone.utc) + timedelta(hours=1)
    maintenance = {"title": "DEMO reviewed maintenance", "startAt": start.isoformat(), "endAt": (start + timedelta(hours=1)).isoformat()}
    api("POST", f"/workspaces/{workspace}/maintenance/{account}/{devices[0]}", maintenance, viewer, 403)
    window = api("POST", f"/workspaces/{workspace}/maintenance/{account}/{devices[0]}", maintenance, owner)
    assert window["deviceId"] == devices[0]
    checks.append("maintenance read capability does not imply write")
    tariff = {"version": 0, "accountId": account, "workspaceId": workspace, "currency": "EUR", "timezone": "UTC", "ratePerKWh": "0.15", "effectiveFrom": (start - timedelta(days=7)).isoformat(), "effectiveTo": (start + timedelta(days=7)).isoformat(), "taxesIncluded": True, "reason": "DEMO reviewed estimate", "archived": False}
    api("POST", "/energy-tariffs", {"tariff": tariff, "reviewed": True}, viewer, 403)
    saved_tariff = api("POST", "/energy-tariffs", {"tariff": tariff, "reviewed": True}, owner)
    assert saved_tariff["version"] == 1
    checks.append("explicit tariff action authorization and immutable initial version")
    source = api("POST", f"/devices/{devices[0]}/data-sources", {"sourceType": "SMART_PLUG", "protocol": "REST", "externalId": "DEMO-confirmed-operations"}, owner)["id"]
    now = datetime.now(timezone.utc)
    fact = {"version": 0, "deviceId": devices[0], "sourceId": source, "sourceEventId": "confirmed-cycle-1", "kind": "CYCLE", "completeness": "COMPLETED", "startAt": (now-timedelta(hours=3)).isoformat(), "endAt": (now-timedelta(hours=2)).isoformat(), "reason": "DEMO confirmed event"}
    command = {"accountId": account, "workspaceId": workspace, "fact": fact, "reviewed": True}
    api("POST", "/operational-events", command, viewer, 403)
    recorded = api("POST", "/operational-events", command, owner)
    retry = api("POST", "/operational-events", command, owner)
    assert retry["eventId"] == recorded["eventId"] and retry["version"] == 1
    api("POST", "/operational-events", {**command, "fact": {**fact, "reason": "different duplicate"}}, owner, 409)
    corrected = api("POST", "/operational-events", {**command, "fact": {**recorded, "reason": "DEMO explicit correction"}}, owner)
    assert corrected["version"] == 2
    incomplete = {**fact, "sourceEventId": "incomplete-cycle", "completeness": "INCOMPLETE", "endAt": None}
    api("POST", "/operational-events/import", {"accountId": account, "workspaceId": workspace, "facts": [incomplete], "reviewed": True}, owner)
    api("POST", "/operational-events/import", {"accountId": account, "workspaceId": workspace, "facts": [incomplete], "reviewed": True}, owner)
    schedule = {"version": 0, "deviceId": devices[0], "timezone": "Europe/Belgrade", "effectiveFrom": (now-timedelta(days=30)).isoformat(), "effectiveTo": (now+timedelta(days=30)).isoformat(), "weekSlots": [{"dayOfWeek": 1, "from": "08:00", "to": "18:00"}], "holidays": [], "reason": "DEMO reviewed schedule"}
    assert api("POST", "/operating-schedules", {"accountId": account, "workspaceId": workspace, "schedule": schedule, "reviewed": True}, owner)["version"] == 1
    checks.append("source-authorized facts, duplicate import, correction, incomplete cycle and schedule persistence")
    definition = {"schemaVersion": 1, "name": "DEMO team publication smoke", "description": "", "defaultContext": {}, "sections": [], "widgets": [], "layouts": {}}
    dashboard = api("POST", "/dashboards", {"definition": definition}, owner, 201, key())["id"]
    publish(owner, dashboard, api("GET", f"/dashboards/{dashboard}/draft", actor=owner))
    api("POST", f"/dashboards/{dashboard}/workspace", {"workspaceId": workspace, "reviewed": True}, owner)

    def audience(capabilities):
        metadata = api("GET", f"/dashboards/{dashboard}/grants", actor=owner)
        entries = [] if not capabilities else [{"subjectId": viewer["id"], "subjectKind": "USER", "capabilities": capabilities}]
        api("PUT", f"/dashboards/{dashboard}/grants", {"audience": entries, "expectedVersion": metadata["version"], "reviewed": True}, owner)

    audience(["VIEW", "EDIT", "COPY", "EXPORT"])
    editor_draft = api("GET", f"/dashboards/{dashboard}/draft", actor=viewer)
    owner_draft = api("GET", f"/dashboards/{dashboard}/draft", actor=owner)
    assert editor_draft["etag"] != owner_draft["etag"]
    publish(viewer, dashboard, editor_draft, 403)
    audience(["VIEW", "EDIT", "PUBLISH", "COPY", "EXPORT"])
    publish(viewer, dashboard, editor_draft)
    publish(owner, dashboard, owner_draft, 409)
    assert dashboard in [item["id"] for item in api("GET", "/dashboards/team", actor=viewer)]
    template = api("GET", f"/dashboards/{dashboard}/export", actor=viewer)
    assert "workspaceId" not in json.dumps(template) and "accountId" not in json.dumps(template)
    api("POST", "/dashboards/import", {"definition": template, "reviewed": True}, viewer, headers=key())
    checks.append("independent author drafts, editor without publish, stale publication conflict, export/import")
    grants = api("GET", f"/workspaces/{workspace}/resource-grants?accountId={account}", actor=owner)
    scoped = next(item for item in grants if item["subjectId"] == team and item["action"] == "device.read")
    api("DELETE", f"/workspaces/{workspace}/resource-grants/{scoped['id']}?accountId={account}", actor=owner)
    assert api("POST", f"/workspaces/{workspace}/devices/query", scope, viewer)["evaluatedCount"] == 0
    audience([])
    api("GET", f"/dashboards/{dashboard}", actor=viewer, expected=403)
    api("POST", f"/dashboards/{dashboard}/query", {}, viewer, 403)
    api("DELETE", f"/workspaces/{workspace}/members/{viewer['id']}", actor=owner)
    api("POST", f"/workspaces/{workspace}/devices/query", scope, viewer, 403)
    checks.append("resource revoke, dashboard revoke and stale JWT after membership removal")
    # A separate active cohort remains for two independent browser sessions.
    for action in ("device.read", "telemetry.read"):
        grant(other["id"], "USER", action, devices[0])
    metadata = api("GET", f"/dashboards/{dashboard}/grants", actor=owner)
    api("PUT", f"/dashboards/{dashboard}/grants", {"audience": [{"subjectId": other["id"], "subjectKind": "USER", "capabilities": ["VIEW", "EDIT"]}], "expectedVersion": metadata["version"], "reviewed": True}, owner)
    draft = api("GET", f"/dashboards/{dashboard}/draft", actor=owner)
    definition["widgets"] = [{"id": "fleet", "type": "fleet-count", "typeVersion": 1, "title": "Authorized equipment", "binding": {"mode": "ALL"}, "query": {}, "presentation": {"visualization": "donut"}, "navigation": {}, "payload": {}}]
    draft = api("PUT", f"/dashboards/{dashboard}/draft", {"definition": definition, "baseRevision": 2}, owner, headers={"If-Match": '"' + draft["etag"] + '"'})
    publish(owner, dashboard, draft)
    owner_panel=api("POST",f"/dashboards/{dashboard}/query",{},owner)
    viewer_panel=api("POST",f"/dashboards/{dashboard}/query",{},other)
    assert owner_panel["panels"][0]["evaluatedCount"]==2 and viewer_panel["panels"][0]["evaluatedCount"]==1
    assert not owner_panel["cached"] and not viewer_panel["cached"]
    current_grants=api("GET",f"/workspaces/{workspace}/resource-grants?accountId={account}",actor=owner)
    read_grant=next(g for g in current_grants if g["subjectId"]==other["id"] and g["action"]=="device.read")
    api("DELETE",f"/workspaces/{workspace}/resource-grants/{read_grant['id']}?accountId={account}",actor=owner)
    revoked=api("POST",f"/dashboards/{dashboard}/query",{},other)
    assert not revoked["cached"] and revoked["panels"][0]["status"]=="no_access" and revoked["panels"][0]["data"] is None
    grant(other["id"],"USER","device.read",devices[0])
    checks.append("per-viewer panel coverage, cache bypass and resource revoke clears panel evidence")
    Path("artifacts").mkdir(exist_ok=True)
    Path("artifacts/workspace-dashboard-smoke-summary.json").write_text(json.dumps({"status": "passed", "checks": checks, "workspaceId": workspace, "dashboardId": dashboard}, indent=2))
    # Local fixtures only; no bearer tokens are persisted or printed.
    Path("artifacts/workspace-dashboard-browser-fixtures.json").write_text(json.dumps({"owner": {"email": owner["email"], "password": owner["password"]}, "viewer": {"email": other["email"], "password": other["password"]}, "workspaceId": workspace, "dashboardId": dashboard}, indent=2))
    print(json.dumps({"status": "passed", "checks": checks}, indent=2))


if __name__ == "__main__":
    main()
