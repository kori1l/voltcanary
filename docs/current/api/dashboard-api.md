# Private dashboard API

Implemented by `dashboard-service` on 8089, through the gateway. Ownership is the
signed current user and their active device account, resolved server-side on each
request against identity and device services. ADMIN/SUPPORT have no private-owner
bypass; caller owner/account fields are ignored. Foreign IDs use the same404 as
missing IDs. Unavailable ownership dependencies return503; inactive actors403;
missing/invalid bearer401. No authorization cache is served during failures.

## Routes

| Method/path | Contract |
| --- | --- |
| GET `/dashboards` | Owner/account cursor metadata list; status ACTIVE(default)/ARCHIVED/ALL; limit20 default, maximum100; items/hasNext/nextCursor; stable createdAt,id order |
| POST `/dashboards` | `{definition}`; required Idempotency-Key; returns201 canonical metadata and a new private author draft |
| GET `/dashboards/{id}` | Metadata, published definition (null before first publish), revision, owner permissions, draftEtag, unsupportedWidgetIds; optional `revision=n` reads authorized immutable history; ETag |
| GET `/dashboards/{id}/draft` | Author draft with baseRevision, etag, definition, updatedAt; ETag is the quoted draft UUID |
| PUT `/dashboards/{id}/draft` | If-Match required; `{baseRevision,definition}` or `{baseRevision,sourceRevision}` for rollback-as-draft; returns saved draft/new ETag |
| POST `/dashboards/{id}/publish` | If-Match equal to the quoted submitted draftEtag, Idempotency-Key and `{draftEtag}` required; returns committed immutable revision; advances draft base and ETag |
| GET `/dashboards/{id}/revisions` | Cursor metadata history descending; limit20/max100, optional exclusive `before`; items/hasNext/nextBefore |
| POST `/dashboards/{id}/copy` | `{name}` plus Idempotency-Key; returns201 independent private draft; no grants copied; inaccessible bindings removed and affected widgets explicitly UNBOUND |
| POST `/dashboards/{id}/archive`, `/restore` | If-Match uses the current draft ETag; soft lifecycle preserves ID/history; restore obeys active quota |
| GET `/dashboard-preferences/me` | defaultDashboardId/favorites/defaultContext/etag/updatedAt/fallbackReason; first empty ETag is zero UUID |
| PUT `/dashboard-preferences/me` | If-Match plus `{defaultDashboardId,favorites,defaultContext}`; only accessible active published dashboards can be selected |

Published representation ETags identify ID/revision/status. Editing and lifecycle
preconditions use **draftEtag**, obtained from the view or draft response, rather
than the published representation ETag. Gateway CORS allows If-Match,
If-None-Match and Idempotency-Key and exposes ETag/Retry-After.

Draft save/publish require the current base revision. Stale ETag/base returns409
with the existing draft/history preserved; missing preconditions428; invalid
fields/bounds or quota422 with errors[{field,message}] where applicable. There
is no silent overwrite. Rollback first loads an authorized historical definition
into a new draft based on the current publication, then Publish appends history.
PostgreSQL also rejects UPDATE/DELETE of published revisions.

Create/publish/copy keys are actor/account/route/body-bound for24h; exact retries
reuse the canonical result. Different bodies with that key return409. Authorization
is renewed before replay and private resource access is rechecked. Expired keys
are discarded on the next actor write; retries after expiry are new commands.
Only key/body hashes and safe outcomes are persisted, not request credentials.

## Definition schema and compatibility

Schema1 contains name1–80, optional description≤500, object defaultContext,
sections (≤24), widgets (≤24), and object layouts. Widget fields are id, type,
positive typeVersion, title≤120, binding, query, presentation, navigation and payload.
Unknown type/version retains safe payload/query/presentation as opaque JSON;
it is inert. View responses list only unavailable types/versions in
unsupportedWidgetIds; the additive typed catalog and query contract follows below.

Layouts use desktop12/tablet8/mobile1 columns with existing unique widgetId and
bounded integer x/y/w/h. Definitions are limited to128KiB, nesting12, object64
fields, arrays500, strings4000. Resource UUID selections are validated and checked
against owner-scoped device/location APIs. Query is an object, never executable
text; concrete business intent allowlists come from the query engine manifests.
Recursive validation rejects credential fields, source tokens, authorization/
headers, delivery recipients, SQL/code/HTML/iframe fields, executable content,
external HTTP URLs and bearer/private-key text, including unsupported payloads.
Only safe internal navigation paths are permitted; device navigation validates
and authorizes canonical UUIDs. No raw telemetry or ACL is copied into definitions.

Ten active dashboards/drafts per user are enforced under a user-wide PostgreSQL
transaction lock, including simultaneous create/restore. Drafts remain separate
from published definitions. Archive clears the personal default/favorite and
returns DEFAULT_ARCHIVED; unavailable default reads omit its ID and explain
DEFAULT_UNAVAILABLE. Restore retains identity but does not automatically reset
the personal default. The existing fixed `/dashboard` UI remains until editor
integration; no browser-storage definitions are imported.

## Verification

Run `mvn -pl services/dashboard-service,services/api-gateway -am test` from backend.
PostgreSQL tests cover fresh Flyway and non-destructive rerun, immutable history,
foreign reads/drafts/revisions/copy, supplied ownership spoof, quota, cursor pages,
concurrent create/publish retry, body conflict, stale ETag/base, rollback, archive/
restore/default and safe unsupported-widget copy after resource access removal.
HTTP ownership tests use signed JWTs and changing identity/account responses to
verify inactive/mismatched actors and dependency failure without cached access.
Gateway tests include publication/draft CORS headers and existing auth behavior.

`powershell -ExecutionPolicy Bypass -File scripts/dashboard-domain-smoke.ps1`
creates two isolated local test users with random credentials held only in memory,
then tests these public routes with real JWTs. Its ignored summary contains only
IDs/statuses. Local health/readiness and this authorization workflow pass; this is
not capacity, team-sharing or production deployment evidence.

## Typed widget queries

`GET /dashboard-widget-types` requires the same active personal actor and returns
versioned manifests: type/typeVersion/title/resultType/supported/dependency,
reducers, canonical units, maxSeries/maxDetailDevices/maxRows and timeBasis.
Supported v1: fleet-count, latest-value, time-series, metric-summary,
incident-list, maintenance-agenda, risk-list, quality-and-freshness,
location-matrix, prediction-evidence and context-note. Energy-period,
peak-and-ranking, estimated-cost and uptime-or-cycle explain their unavailable
dependencies. Unknown versions remain inert and do not call evidence owners.

`POST /dashboards/{id}/query` accepts an object with optional `revision`,
`draftEtag`, `widgetIds`, `runtimeContext` and `forceRefresh`. Omit revision to
read the current published revision. Choose revision or the current author
draftEtag; stale draft references return409. Widget IDs select only saved widgets;
the request cannot supply widget definitions, endpoints, SQL or source code.
`POST /dashboards/{id}/query-validation` uses the same reference and authorization,
returns valid/invalid/unsupported panels, resolves current device metadata and
registered telemetry capabilities with an empty-device read, and does not read
measurement, incident, prediction or maintenance facts.

```json
{
  "revision": 1,
  "widgetIds": ["fleet", "kitchen"],
  "runtimeContext": {
    "scope": {"mode": "DYNAMIC", "locationIds": ["00000000-0000-0000-0000-000000000001"], "includeSubtree": true},
    "time": {"mode": "CALENDAR", "preset": "TODAY", "timezone": "Europe/Belgrade"}
  }
}
```

Scopes are typed ALL/DYNAMIC/FIXED/UNBOUND with deviceIds/locationIds,
includeSubtree, deviceTypes and locationTags. Device tags are unavailable;
locationTags belong to location metadata. All scope restrictions intersect the
current active device account. A FIXED widget retains its visible binding when
runtime filters change, while dashboard restrictions and current resource rights
still apply. UNBOUND remains empty. Names and values from denied bindings are
purged. ADMIN/SUPPORT cannot expand personal scope.

Query fields are metric, unit, reducer, time, compare, rows, offset,
freshnessPolicy, cadenceSeconds, incidentMode, series[{deviceId,metric}] and
columns. Unsupported fields or reducers/units fail that panel. Registered metric
keys and observed canonical units are checked with the telemetry owner; missing
metric evidence has no value. Max24 widgets,8series per widget,500 scope devices,
50 detail devices (latest-value requires1),50 rows/page. Ranking remains disabled
with the energy dependency. Budget overflows reject the batch with422 and
LIMIT_EXCEEDED before evidence fanout, without silent truncation. Other invalid
widget intent and owner failures remain independent panel results.

Time uses INHERITED/RELATIVE/CALENDAR/FIXED and LAST_24_HOURS/LAST_7_DAYS/
LAST_30_DAYS/TODAY/WEEK_TO_DATE/MONTH_TO_DATE. NEXT_7_DAYS/NEXT_30_DAYS are for
maintenance only. All reads share one asOf. UTC intervals are half-open [from,to),
positive and at most31 days. Calendar starts use the requested IANA zone and DST;
previous comparison uses equal elapsed duration, manual comparison supplies its
own explicit range. Difference and percent are separate nullable fields;
zero_baseline has no percent. Celsius never has ratio percentages. Units are
never silently converted or combined.

The batch returns dashboardId/revision/draftEtag/contextHash/asOf/returnedAt/
cached/panels. Each panel has widgetId, status (ready/no_data/unavailable/
no_access/unsupported/invalid), nullable data, contextHash, resolved time,
requestedCount/evaluatedCount, receivedAt/measuredAt, provenance,
errorCode/explanation. No-access and failed panels contain no previous data.
Client request generations, AbortSignal and session identity changes discard
obsolete responses; clocks are evidence timestamps, not freshness guarantees.

Result data contains resultType, scoped device/location metadata and qualified
owner sources. Fleet returns registration counts separately from measurement
age; sparse/manual-import are explicit states, not online/healthy claims.
Periodic freshness uses max(3×cadence,60s), or a labeled dashboard default300s.
Latest preserves zero and exact source/time/unit/quality. Series return at most
200 observed min/max/mean bins with actual endpoint/extrema timestamps and gap
counts; no interpolation. Summaries use every qualified accepted sample,
one-sample-one-weight mean and p95, distinct evidence-device coverage, extrema
timestamps and explicit comparisons, never a displayed-curve average.

Incidents distinguish current OPEN/ACKNOWLEDGED, critical-open and event-created
interval counts; notification attempts are not incidents. Tables retain full
totals independently of page rows. Maintenance uses half-open window overlap,
distinct operations and separate suppressAlerts/excludeFromAnalytics/excludeFromMl
flags. Raw telemetry is not removed by analytics exclusions. Risk and prediction
read stored records only, with model/family/horizon/status/path/fallback/lineage
when recorded. Risk is not a failure probability; confidence is not accuracy and
is unavailable for non-ready prediction status. The matrix is single-account;
energy is null with an unsupported-dependency state. Notes are plain text≤1000
with safe internal navigation.

Query actor resolution reads identity `/auth/me` and an UNBOUND device scope;
it does not use the account-creating `/accounts/current` command fallback.
Owner read adapters call fixed internal POST paths: device `/internal/dashboard/scope`
and `/maintenance`, telemetry `/internal/dashboard/telemetry`, analytics
`/internal/dashboard/analytics`, alert `/internal/dashboard/incidents`. They require
both the internal service token and the signed viewer bearer even when legacy
authorization toggles are disabled. Each owner rechecks active account/device
scope; no caller owner header bypass. The common-web DTO is an additive wire
contract, not a shared query/business engine. Queries do not create accounts,
jobs, inferences, recalculations, notifications or rotate source credentials.

Identical owner reads within a batch share one future. The JVM allows six owner
reads and a bounded queue; panels have a five-second evidence deadline, HTTP reads
four seconds, and owner SQL statements three seconds. Scope and actor authorization
run before cache lookup. A cache key partitions actor/account, immutable revision
or draftETag, complete definition, typed runtime and fresh authorized metadata
fingerprint. Up to64 entries live30s; snapshots retain their original asOf and
contextHash, with cached=true/new returnedAt. Failed batches are not cached;
no-access purges the actor's entries. These are owner read snapshots sharing a
query asOf, not a distributed transaction or reconstructed incident history.

Run `scripts/dashboard-query-profile.ps1 -FailureProfile` against local Compose.
It creates an isolated user,100 synthetic devices and14,400 accepted samples for
12 widgets/24h; credentials remain in memory. It measures cold forced reads,
ten authorization-renewed warm reads and an actual stopped telemetry dependency,
restoring telemetry in finally. Sanitized ignored evidence reports milliseconds,
panel states and whether cold≤2s/warm p95≤5s were met. It makes no ingestion,
soak, scaling, production or capacity claim.
