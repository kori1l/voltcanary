"""Verify native imports, migration and history through Compose APIs; no model calls."""
import argparse
import copy
import json
import importlib.util
from pathlib import Path
import secrets
import time
from urllib.request import Request, urlopen
import uuid

from ai_dashboard_eval.api import ApiError, Client, ROOT, write_json

owner_spec = importlib.util.spec_from_file_location("configurable_owner_smoke", Path(__file__).with_name("configurable-owner-docker-smoke.py"))
owner_smoke = importlib.util.module_from_spec(owner_spec)
owner_spec.loader.exec_module(owner_smoke)
apply_review, ensure, wait_for_services = owner_smoke.apply_review, owner_smoke.ensure, owner_smoke.wait_for_services


def register(anonymous, fixture_id):
    email, password = f"native-import-{fixture_id}@example.test", "SmokeA9!" + secrets.token_hex(20)
    anonymous.call("POST", "/auth/register", {"email": email, "password": password})
    login = anonymous.call("POST", "/auth/login", {"email": email, "password": password})
    return Client(anonymous.gateway, login["accessToken"], timeout=30, refresh_token=login.get("refreshToken"))


def verify_sharing(client, viewer, run_id, account, dataset_id, field, restricted, visible_row, query):
    workspace = client.call("POST", "/workspaces", {"name": "Synthetic native sharing"})["id"]
    viewer_id = viewer.call("GET", "/auth/me")["id"]
    viewer.call("GET", "/accounts/current")
    client.call("PUT", f"/workspaces/{workspace}/members", {"userId": viewer_id, "role": "MEMBER", "reviewed": True})
    client.call("POST", f"/workspaces/{workspace}/resource-accounts", {"accountId": account, "active": True, "reviewed": True})
    owner_base = f"/data/datasets/{dataset_id}"
    share_key = run_id + "-share"
    review = client.call("POST", owner_base + "/sharing-reviews", {
        "idempotencyKey": share_key, "definition": {"workspaceId": workspace, "subject": {"kind": "USER", "id": viewer_id},
            "policy": {"readFieldIds": [field], "writeFieldIds": [], "actions": ["READ"],
                       "rows": {"mode": "IDS", "recordIds": [visible_row["recordId"]]}}}})
    share_path = owner_base + f"/sharing-reviews/{review['reviewId']}/apply"
    confirmation = {"reviewRevision": review["reviewRevision"], "reviewDigest": review["reviewDigest"], "idempotencyKey": share_key}
    grant = client.call("POST", share_path, confirmation)
    grant_id = review["grantId"]
    ensure(grant["status"] == "SUCCEEDED" and grant["grantId"] == grant_id, "SHARING_GRANT_NOT_COMMITTED")
    base = f"/data/workspaces/{workspace}/accounts/{account}/datasets/{dataset_id}"
    visibility_path = f"{base}/query?grantId={grant_id}"
    described = viewer.call("GET", f"{base}?grantId={grant_id}")
    ensure([f["id"] for f in described["fields"]] == [field], "SHARED_SCHEMA_LEAKS_RESTRICTED_FIELD")
    shared = viewer.call("POST", visibility_path, {"document": query})
    ensure(shared["rows"] == [visible_row] and shared["completeness"] == "COMPLETE", "SHARED_ROW_SCOPE_MISMATCH")
    outcomes = []
    for probe in (restricted, str(uuid.uuid4())):
        expanded = copy.deepcopy(query)
        expanded["spec"]["select"].append(probe)
        try:
            viewer.call("POST", visibility_path, {"document": expanded})
        except ApiError as failure:
            outcomes.append((failure.status, failure.code))
        else:
            raise ApiError(0, "SHARED_QUERY_LEAKS_RESTRICTED_FIELD")
    ensure(outcomes[0] == outcomes[1] and outcomes[0][0] in (400, 403, 422), "SHARED_FIELD_DISCOVERY_LEAK")
    revoke_path = owner_base + f"/sharing/{grant_id}/revoke"
    revoke_payload = {"expectedRevision": 1, "idempotencyKey": run_id + "-revoke-share"}
    revoked = client.call("POST", revoke_path, revoke_payload)
    ensure(client.call("POST", revoke_path, revoke_payload) == revoked, "SHARING_REVOKE_REPLAY_MISMATCH")
    try:
        viewer.call("POST", visibility_path, {"document": query})
    except ApiError as failure:
        ensure(failure.status in (403, 404), "REVOKED_SHARING_WRONG_OUTCOME")
    else:
        raise ApiError(0, "REVOKED_SHARING_REMAINED_READABLE")


def verify_workbench_views(client, viewer, run_id, dataset_path, query):
    zones = "/data/spatial-features/circles"
    ids = {str(uuid.uuid4()), str(uuid.uuid4())}
    geometry = {"longitude": 20.46, "latitude": 44.81, "radiusMetres": "50"}
    for circle in ids:
        saved = client.call("PUT", f"/data/spatial-features/{circle}/circle",
                            {"expectedRevision": 0, "geometry": geometry})
        ensure(saved["revision"] == 1 and saved["geometry"] == geometry, "CIRCLE_WRITE_MISMATCH")
    first = client.call("GET", zones + "?limit=1")
    ensure(len(first["items"]) == 1 and first["nextAfter"], "CIRCLE_CURSOR_MISSING")
    second = client.call("GET", zones + "?limit=1&after=" + first["nextAfter"])
    ensure({first["items"][0]["id"], second["items"][0]["id"]} == ids and not second["nextAfter"],
           "CIRCLE_CURSOR_MISMATCH")
    ensure(viewer.call("GET", zones)["items"] == [], "CIRCLE_LIST_SCOPE_CHANGED")

    widget_query = copy.deepcopy(query)
    widget_query["spec"]["limit"] = 25
    owner_rows = client.call("POST", dataset_path + "/query", {"document": widget_query})["rows"]
    widget = {"id": "rows", "type": "configured-records", "typeVersion": 1, "title": "Native table",
              "binding": {"mode": "DYNAMIC"}, "query": widget_query, "presentation": {}, "navigation": {}, "payload": {}}
    definition = {"schemaVersion": 1, "name": "Synthetic table dashboard", "description": "", "defaultContext": {},
                  "sections": [], "widgets": [widget], "layouts": {}}
    result = client.call("POST", "/dashboards", {"definition": definition}, {"Idempotency-Key": run_id + "-dashboard"})
    path = "/dashboards/" + result["id"]
    draft = client.call("GET", path + "/draft")
    parameters = {"draftEtag": draft["etag"]}
    validation = client.call("POST", path + "/query-validation", parameters)["panels"][0]
    ensure(validation["status"] == "valid", "TABLE_WIDGET_VALIDATION_FAILED")
    preview = client.call("POST", path + "/query", parameters)["panels"][0]
    ensure(preview["status"] == "ready" and preview["data"]["configuredRecords"]["rows"] == owner_rows,
           "TABLE_WIDGET_DRAFT_READBACK_MISMATCH")
    client.call("POST", path + "/publish", parameters,
                {"If-Match": '"' + draft["etag"] + '"', "Idempotency-Key": run_id + "-dashboard-publish"})
    panel = client.call("POST", path + "/query", {})["panels"][0]
    ensure(panel["status"] == "ready" and panel["data"]["configuredRecords"]["rows"] == owner_rows,
           "TABLE_WIDGET_PUBLISHED_READBACK_MISMATCH")
    try:
        viewer.call("POST", path + "/query", {})
    except ApiError as failure:
        ensure(failure.status in (403, 404), "TABLE_WIDGET_SCOPE_WRONG_OUTCOME")
    else:
        raise ApiError(0, "TABLE_WIDGET_SCOPE_LEAK")


def run(args):
    wait_for_services(args.gateway, args.timeout, (("dashboard", args.dashboard_port),))
    run_id = uuid.uuid4().hex
    anonymous = Client(args.gateway, "", timeout=30)
    client = register(anonymous, run_id)
    account = client.call("GET", "/accounts/current")["id"]
    key = run_id + "-table"
    reservation = client.call("POST", "/data/review-reservations", {
        "kind": "COLLECTION", "fieldKeys": ["name"], "idempotencyKey": key})
    field = reservation["fieldIdsByKey"]["name"]
    dataset_id = reservation["resourceId"]
    document = {"language": "VCL/1", "kind": "COLLECTION", "spec": {
        "id": dataset_id, "revision": 1, "label": "Synthetic native import checkpoint",
        "fields": [{"id": field, "label": "Name", "type": "TEXT", "maxLength": 120, "nullable": False}],
        "keyFieldIds": [field], "storage": {"mode": "NATIVE", "history": "REVISIONS", "indexedFieldIds": [field]}}}
    review = client.call("POST", "/data/dataset-reviews", {"reservationId": reservation["reviewId"], "document": document})
    apply_review(client, "/data/reviews", review, key)
    base = f"/data/datasets/{dataset_id}"

    def prepare(content, mode, target_dataset=None, columns=None):
        selected_dataset = target_dataset or dataset_id
        selected_base = f"/data/datasets/{selected_dataset}"
        artifact_key = uuid.uuid4().hex
        request = Request(client.gateway + "/data/import-artifacts", content.encode(), {
            "Authorization": "Bearer " + client.token, "Content-Type": "application/octet-stream",
            "Idempotency-Key": artifact_key}, method="POST")
        with urlopen(request, timeout=30) as response:
            artifact = json.load(response)
        head = client.call("GET", selected_base)["definition"]
        selected_columns = columns or [{"sourceColumn": "name", "sourceType": "TEXT", "sourceUnit": None,
                                       "targetFieldId": field, "emptyValue": "PRESERVE_EMPTY", "conversions": [{"method": "identity"}]}]
        import_key = uuid.uuid4().hex
        payload = {"language": "VCL/1", "kind": "IMPORT", "spec": {
            "contractVersion": "IMPORT/1", "artifactId": artifact["id"], "artifactSha256": artifact["sha256"],
            "target": {"datasetId": selected_dataset, "schemaRevision": head["revision"]}, "mappingRevision": 1,
            "format": {"kind": "CSV", "encoding": "UTF-8", "delimiter": ",", "header": [column["sourceColumn"] for column in selected_columns]},
            "mode": mode, "keyFields": head["keyFieldIds"], "nullPolicy": "SCHEMA_EXPLICIT", "conflictPolicy": "REJECT_STALE",
            "columns": selected_columns,
            "limits": {"maxRows": 500, "maxBytes": 1048576, "maxRowBytes": 1000},
            "provenance": {"sourceId": str(uuid.UUID(run_id)), "description": "Synthetic API import checkpoint"},
            "targetPrecondition": {"mode": "NATIVE", "generation": head["generation"], "dataVersion": head["dataVersion"]}}}
        prepared = client.call("POST", selected_base + "/import-reviews", {"idempotencyKey": import_key, "document": payload})
        ensure(prepared["status"] == "PREPARED", "IMPORT_NOT_PREPARED")
        return prepared, import_key

    def apply(prepared, import_key):
        path = f"/data/datasets/{prepared['datasetId']}/import-reviews/{prepared['id']}"
        result = client.call("POST", path + "/apply", {
            "reviewRevision": 1, "reviewDigest": prepared["reviewDigest"], "idempotencyKey": import_key})
        ensure(result["status"] == "SUCCEEDED", "IMPORT_NOT_COMMITTED")
        ensure(client.call("GET", path) == result, "IMPORT_RECEIPT_READBACK_MISMATCH")
        return result

    initial, initial_key = prepare("name\nalpha\nbeta\n", "ATOMIC_INSERT")
    first = apply(initial, initial_key)
    head = client.call("GET", base)["definition"]
    query = {"language": "VCL/1", "kind": "QUERY", "spec": {
        "target": {"datasetId": dataset_id, "schemaRevision": 1}, "select": [field],
        "where": {"op": "CONST", "literal": {"type": "BOOLEAN", "value": True}},
        "order": [{"fieldId": head["systemFields"]["recordIdFieldId"], "direction": "ASC"}],
        "limit": 100, "cursor": None, "read": {"mode": "LIVE"}}}

    def rows(selection):
        page = client.call("POST", base + "/query", {"document": selection})
        ensure(page["completeness"] == "COMPLETE", "IMPORT_QUERY_INCOMPLETE")
        return {row["values"][field]["value"]: row for row in page["rows"]}

    original = rows(query)
    ensure(set(original) == {"alpha", "beta"}, "ATOMIC_INSERT_READBACK_MISMATCH")
    sealed = client.call("POST", base + "/query-snapshots", {"document": query})
    stale, stale_key = prepare("name\nold-pending\n", "ATOMIC_UPSERT")
    replacement, replacement_key = prepare("name\nbeta\ngamma\n", "STAGED_REPLACE")
    ensure(replacement["typedDiff"]["deletes"] == 1, "REPLACEMENT_DELETION_IMPACT_MISSING")
    swapped = apply(replacement, replacement_key)
    ensure(apply(replacement, replacement_key) == swapped, "REPLACEMENT_REPLAY_MISMATCH")
    ensure(swapped["result"]["generation"] != first["result"]["generation"], "GENERATION_DID_NOT_CHANGE")
    current = rows(query)
    ensure(set(current) == {"beta", "gamma"}, "REPLACEMENT_READBACK_MISMATCH")
    ensure(current["beta"]["recordId"] == original["beta"]["recordId"] and current["beta"]["recordVersion"] == "2",
           "REPLACEMENT_STABLE_IDENTITY_MISMATCH")
    pinned = copy.deepcopy(query)
    pinned["spec"]["read"] = {"mode": "SNAPSHOT", "snapshotId": sealed["snapshotId"]}
    ensure(rows(pinned) == original, "RETIRED_GENERATION_SNAPSHOT_CHANGED")
    try:
        apply(stale, stale_key)
    except ApiError as failure:
        ensure(failure.status == 409 and failure.code == "VALIDATION_STALE", "STALE_IMPORT_WRONG_OUTCOME")
    else:
        raise ApiError(0, "STALE_IMPORT_COMMITTED")
    upsert, upsert_key = prepare("name\nbeta\ngamma\n", "ATOMIC_UPSERT")
    touched = apply(upsert, upsert_key)
    ensure(touched["result"]["generation"] == swapped["result"]["generation"], "UPSERT_CHANGED_GENERATION")
    after = rows(query)
    ensure(all(after[name]["recordId"] == current[name]["recordId"] for name in current), "KEY_ONLY_UPSERT_CHANGED_IDS")
    ensure(after["beta"]["recordVersion"] == "3" and after["gamma"]["recordVersion"] == "2", "KEY_ONLY_UPSERT_VERSION_MISMATCH")
    reference_key = run_id + "-references"
    reference = client.call("POST", "/data/review-reservations", {"kind": "COLLECTION", "fieldKeys": ["name", "related"], "idempotencyKey": reference_key})
    names = reference["fieldIdsByKey"]
    reference_id = reference["resourceId"]
    reference_schema = {"language": "VCL/1", "kind": "COLLECTION", "spec": {
        "id": reference_id, "revision": 1, "label": "Synthetic imported references",
        "fields": [{"id": names["name"], "label": "Name", "type": "TEXT", "maxLength": 120, "nullable": False},
                   {"id": names["related"], "label": "Related", "type": "REF", "nullable": False,
                    "target": {"kind": "DATASET", "datasetId": dataset_id}}],
        "keyFieldIds": [names["name"]], "storage": {"mode": "NATIVE", "history": "NONE", "indexedFieldIds": [names["name"], names["related"]]}}}
    reference_review = client.call("POST", "/data/dataset-reviews", {"reservationId": reference["reviewId"], "document": reference_schema})
    apply_review(client, "/data/reviews", reference_review, reference_key)
    columns = [{"sourceColumn": "name", "sourceType": "TEXT", "sourceUnit": None, "targetFieldId": names["name"],
                "emptyValue": "PRESERVE_EMPTY", "conversions": [{"method": "identity"}]},
               {"sourceColumn": "related", "sourceType": "TEXT", "sourceUnit": None, "targetFieldId": names["related"],
                "emptyValue": "REJECT", "conversions": [{"method": "resolve_ref", "target": {"datasetId": dataset_id, "schemaRevision": 1},
                "keyFieldId": field, "cardinality": "EXACT_ONE"}]}]
    linked, linked_key = prepare("name,related\nlinked,beta\n", "ATOMIC_INSERT", reference_id, columns)
    ensure(len(linked["typedDiff"]["referenceReadSet"]) == 1, "REFERENCE_READ_CUT_MISSING")
    apply(linked, linked_key)
    reference_head = client.call("GET", f"/data/datasets/{reference_id}")["definition"]
    reference_query = copy.deepcopy(query)
    reference_query["spec"]["target"]["datasetId"] = reference_id
    reference_query["spec"]["select"] = [names["name"], names["related"]]
    reference_query["spec"]["order"][0]["fieldId"] = reference_head["systemFields"]["recordIdFieldId"]
    page = client.call("POST", f"/data/datasets/{reference_id}/query", {"document": reference_query})
    ensure(len(page["rows"]) == 1 and page["rows"][0]["values"][names["related"]]["value"] == after["beta"]["recordId"], "RESOLVED_REFERENCE_ID_MISMATCH")
    stale_reference, stale_reference_key = prepare("name,related\nstale-link,gamma\n", "ATOMIC_INSERT", reference_id, columns)
    source_update, source_update_key = prepare("name\nbeta\ngamma\n", "ATOMIC_UPSERT")
    apply(source_update, source_update_key)
    try:
        apply(stale_reference, stale_reference_key)
    except ApiError as failure:
        ensure(failure.status == 409 and failure.code == "VALIDATION_STALE", "REFERENCE_CUT_WRONG_OUTCOME")
    else:
        raise ApiError(0, "STALE_REFERENCE_IMPORT_COMMITTED")
    pre_migration = rows(query)
    before_schema = client.call("GET", base)
    migration_key = run_id + "-migration"
    migration = client.call("POST", base + "/migration-reviews", {
        "fieldKeys": ["restricted"], "idempotencyKey": migration_key})
    restricted = migration["fields"][0]["id"]
    migration_path = base + "/migration-reviews/" + migration["id"]
    schema_spec = copy.deepcopy(before_schema["definition"])
    for runtime_field in ("generation", "dataVersion", "systemFields"):
        schema_spec.pop(runtime_field, None)
    schema_spec.update(revision=2, label="Synthetic migrated import table")
    schema_spec["storage"]["indexedFieldIds"] = []
    schema_spec["fields"].append({"id": restricted, "label": "Restricted", "type": "TEXT", "maxLength": 120, "nullable": True})
    restricted_value = {"type": "TEXT", "value": "synthetic owner-visible value"}
    reviewed = client.call("POST", migration_path + "/prepare", {
        "schema": {"language": "VCL/1", "kind": "COLLECTION", "spec": schema_spec},
        "mappings": [{"targetFieldId": restricted, "literal": restricted_value}], "dataMode": "PRESERVE"})
    ensure(reviewed["status"] == "PREPARED" and reviewed["impact"]["indexesChanged"]
           and reviewed["impact"]["addedFieldIds"] == [restricted],
           "SCHEMA_MIGRATION_NOT_PREPARED")
    ensure({cut["owner"] for cut in reviewed["impact"]["owners"]} == {"analytics", "alerts", "dashboards"},
           "SCHEMA_MIGRATION_DEPENDENCY_OWNERS_MISSING")
    confirmation = {"reviewRevision": reviewed["reviewRevision"], "reviewDigest": reviewed["reviewDigest"],
                    "idempotencyKey": migration_key}
    migrated = client.call("POST", migration_path + "/apply", confirmation)
    ensure(migrated["status"] == "SUCCEEDED" and migrated["receipt"]["schemaRevision"] == 2,
           "SCHEMA_MIGRATION_NOT_COMMITTED")
    ensure(client.call("POST", migration_path + "/apply", confirmation) == migrated, "SCHEMA_MIGRATION_REPLAY_MISMATCH")
    ensure(rows(pinned) == original, "MIGRATED_SCHEMA_SNAPSHOT_CHANGED")
    next_query = copy.deepcopy(query)
    next_query["spec"]["target"]["schemaRevision"] = 2
    after_migration = rows(next_query)
    ensure({name: row["recordId"] for name, row in pre_migration.items()} ==
           {name: row["recordId"] for name, row in after_migration.items()}, "SCHEMA_MIGRATION_CHANGED_RECORD_IDS")
    owner_query = copy.deepcopy(next_query)
    owner_query["spec"]["select"].append(restricted)
    ensure(all(row["values"][restricted] == restricted_value for row in rows(owner_query).values()),
           "SCHEMA_MIGRATION_LITERAL_NOT_APPLIED")
    historical = client.call("POST", base + "/history/query", {
        "generation": before_schema["generation"], "throughDataVersion": before_schema["dataVersion"],
        "document": query})
    ensure(historical["completeness"] == "COMPLETE" and historical["readManifest"]["consistency"] == "HISTORY"
           and {row["values"][field]["value"]: row for row in historical["rows"]} == pre_migration,
           "SCHEMA_MIGRATION_HISTORY_CHANGED")
    chunked, chunk_key = prepare("name\n" + "".join(f"chunk-{i:03d}\n" for i in range(101)), "CHUNKED_UPSERT")
    chunk_apply_path = base + f"/import-reviews/{chunked['id']}/apply"
    chunk_confirmation = {"reviewRevision": 1, "reviewDigest": chunked["reviewDigest"], "idempotencyKey": chunk_key}
    running = client.call("POST", chunk_apply_path, chunk_confirmation)
    ensure(running["status"] == "RUNNING", "CHUNKED_JOB_DID_NOT_START")
    job_path = f"/data/jobs/{chunked['id']}"
    deadline = time.monotonic() + min(args.timeout, 180)
    completed = client.call("GET", job_path)
    while completed["status"] == "RUNNING" and time.monotonic() < deadline:
        time.sleep(1)
        completed = client.call("GET", job_path)
    ensure(completed["status"] == "SUCCEEDED" and completed["progress"]["committedRows"] == 101,
           "CHUNKED_WORKER_DID_NOT_COMPLETE")
    receipts = completed["result"]["chunks"]
    ensure([(r["chunkIndex"], r["fromRow"], r["throughRow"]) for r in receipts] == [(0, 0, 100), (1, 100, 101)],
           "CHUNKED_RECEIPT_RANGES_INVALID")
    ensure(client.call("POST", chunk_apply_path, chunk_confirmation) == completed, "CHUNKED_JOB_REPLAY_MISMATCH")
    lease_path = base + "/history/leases"
    lease_request = {"generation": before_schema["generation"], "throughDataVersion": before_schema["dataVersion"],
                     "durationSeconds": 300, "idempotencyKey": run_id + "-lease"}
    lease = client.call("POST", lease_path, lease_request)
    ensure(lease["status"] == "ACTIVE" and client.call("POST", lease_path, lease_request) == lease,
           "HISTORY_LEASE_REPLAY_MISMATCH")
    archive = client.call("POST", base + "/migration-reviews", {"fieldKeys": [], "idempotencyKey": run_id + "-archive"})
    archive_review = client.call("POST", base + f"/migration-reviews/{archive['id']}/prepare",
                                 {"schema": None, "mappings": [], "dataMode": "ARCHIVE"})
    impact = archive_review["impact"]
    ensure(archive_review["status"] == "FAILED" and impact["blocked"] and impact["activeHistoryLeases"] == 1
           and any(ref["datasetId"] == reference_id for ref in impact["incomingReferences"])
           and impact["retainedSnapshots"] >= 1, "ARCHIVE_PROTECTION_MISSING")
    release = client.call("DELETE", lease_path + "/" + lease["id"])
    ensure(release["status"] == "RELEASED", "HISTORY_LEASE_RELEASE_FAILED")
    viewer = register(anonymous, run_id + "-viewer")
    verify_sharing(client, viewer, run_id, account, dataset_id, field, restricted,
                   after_migration["beta"], next_query)
    reference_base = f"/data/datasets/{reference_id}"
    archive_key = run_id + "-archive-reference"
    archive = client.call("POST", reference_base + "/migration-reviews", {"fieldKeys": [], "idempotencyKey": archive_key})
    archived_review = client.call("POST", reference_base + f"/migration-reviews/{archive['id']}/prepare",
                                  {"schema": None, "mappings": [], "dataMode": "ARCHIVE"})
    ensure(archived_review["status"] == "PREPARED", "ELIGIBLE_ARCHIVE_NOT_PREPARED")
    archive_confirmation = {"reviewId": archive["id"], "reviewRevision": archived_review["reviewRevision"],
                            "reviewDigest": archived_review["reviewDigest"], "idempotencyKey": archive_key}
    archived = client.call("POST", reference_base + "/archive", archive_confirmation)
    ensure(archived["status"] == "SUCCEEDED" and archived["receipt"]["status"] == "ARCHIVED"
           and archived["receipt"]["retainedData"], "ARCHIVE_NOT_COMMITTED")
    ensure(client.call("POST", reference_base + "/archive", archive_confirmation) == archived, "ARCHIVE_REPLAY_MISMATCH")
    ensure(any(table["id"] == reference_id for table in client.call("GET", "/data/datasets/archived")), "ARCHIVE_NOT_LISTED")
    verify_workbench_views(client, viewer, run_id, base, owner_query)
    return {"status": "PASS", "fixtureRunId": run_id, "fixtureAccountId": account, "fixtureDatasetId": dataset_id,
            "atomicInsert": True, "generationReplacement": True, "stableIds": True, "sameReceiptReplay": True,
            "sealedSnapshotPreserved": True, "staleImportRejected": True, "keyOnlyUpsert": True,
            "referenceResolution": True, "referenceCutRevalidated": True, "fixtureReferenceDatasetId": reference_id,
            "schemaMigration": True, "dependencyOwners": True, "historicalCutPreserved": True, "chunkedWorker": True,
            "historyLease": True, "archiveProtection": True, "workspaceRowScope": True, "workspaceFieldScope": True, "archive": True,
            "circleCatalog": True, "configuredRecordsDashboard": True,
            "inputData": "SYNTHETIC", "liveModelCalls": 0}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--dashboard-port", type=int, default=8089)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--summary", type=Path, default=ROOT / "artifacts/native-import-smoke-summary.json")
    args = parser.parse_args()
    try:
        summary = run(args)
    except (ApiError, OSError, ValueError, KeyError) as failure:
        summary = {"status": "FAIL", "code": failure.code if isinstance(failure, ApiError) else "IMPORT_SMOKE_RUNTIME_INVALID"}
    write_json(args.summary, summary)
    print(json.dumps(summary))
    raise SystemExit(0 if summary["status"] == "PASS" else 1)
