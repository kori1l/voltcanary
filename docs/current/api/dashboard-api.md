# Private dashboard API

Implemented by `dashboard-service` on 8089, through the gateway. Ownership is the
signed current user and their active device account, resolved server-side on each
request against identity and device services. ADMIN/SUPPORT have no private-owner
bypass; caller owner/account fields are ignored. Foreign IDs use the same404 as
missing IDs. Unavailable ownership dependencies return503; inactive actors403;
missing/invalid bearer401. No authorization cache is served during failures.

## Deletion

Deletion is separate from reversible archive. Only the exact owner/account receives the `delete` UI permission; delegated capabilities do not confer deletion. Missing/foreign/deleted IDs return404, missing If-Match428 and stale draft tokens409. The owner default/favorites are cleared; other users unavailable preferences are filtered during ordinary reads. Confirmation is available from catalogue actions and viewer/editor settings. Failed requests retain confirmation and require refreshing the saved token. Docker and browser verification are deferred at the user request.

## Routes

| Method/path | Contract |
| --- | --- |
| GET `/dashboards` | Owner/account cursor metadata list; status ACTIVE(default)/ARCHIVED/ALL; limit20 default, maximum100; items/hasNext/nextCursor; stable createdAt,id order |
| POST `/dashboards` | `{definition}`; required Idempotency-Key; returns201 canonical metadata and a new private author draft |
| GET `/dashboards/{id}` | Metadata, published definition (null before first publish), revision, owner permissions, draftEtag, unsupportedWidgetIds; optional `revision=n` reads authorized immutable history; ETag |
| DELETE `/dashboards/{id}` | Exact owner/account only; quoted saved draft ETag in If-Match; returns204. Marks the dashboard inaccessible, including shared reads, archived lists and restore; retains immutable history/audit internally. Devices and telemetry are unaffected. |
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

Wire layouts accept desktop12/tablet8/mobile1 columns for compatibility; the
visual editor uses12/6/1 and clamps legacy tablet placements into six columns.
Layouts have existing unique widgetId and
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
the personal default. `/dashboard` resolves the authorized personal default;
missing or archived defaults show the system overview with a fallback reason.
`/dashboard?overview=true` opens that overview explicitly. `/dashboards` lists
personal metadata; `/dashboards/{id}` views publication and `/edit` edits the
private author draft. No browser-storage definitions are imported automatically.

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
reducers, canonical units, maxSeries/maxDetailDevices/maxRows, timeBasis and
additive sizeConstraints (desktopMinColumns/tabletMinColumns/mobileMinColumns/minRows).
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
cached/panels/scopes. Each panel has widgetId, status (ready/no_data/unavailable/
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
fingerprint and selected widget IDs. Read keys normalize unused pagination and
select only required telemetry latest/summaries/quality/series. Latest-only reads
use asOf without unrelated comparison/pagination fields. Observed metric keys
and indexed account/null-account candidates preserve historical latest values
without a catalogue-only restriction or history cutoff. Up to64 compact entries
live30s within a16MB serialized-byte LRU budget (8MB per entry). Private cost and
operations also renew current tariff/fact/schedule version fingerprints before
reuse, including comparison facts. Workspace evidence remains uncached; snapshots retain their original asOf and
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

## Versioned business task templates

GET /dashboard-templates renews the signed active personal actor through the
same ownership boundary before returning shared, resource-free v1 definitions.
Each entry carries id/version/title/task/persona/segments, available/explanation,
period/seriesHours, symbolic slots and panels (key/type/title/slot/metric/unit,
query defaults and safe internal drill-down). Symbols are primary/equipment/
location; templates contain no owner/account/device UUID or credentials.
Shift, My devices, Refrigeration and Technician are available. Energy is gated
by the actual energy-period manifest; it is unavailable until verified methods
exist. Persona/business labels never change permissions.

The binding wizard resolves current authorized device/location choices and
registered metrics/canonical units, rejects removed or foreign selections and
duplicate devices, and reports the resolved scope before creation. A location
selection covers its exact location; descendants must be explicitly selected.
Missing panels may be removed or kept annotated with UNBOUND and no_data;
no owner measurement read occurs for an explicitly unbound panel. The private
create endpoint rechecks all canonical resource references. A source-version
context note is copied with the independent definition; subsequent template
changes do not mutate its draft or immutable publication. Transport retries use
the same reviewed definition (including widget IDs) and idempotency key.

Shift power series uses a rolling LAST_6_HOURS preset; other measurement task
panels use24h, and maintenance has its own NEXT_7_DAYS interval. Celsius and
power remain separate panels. My devices supports up to six distinct equipment
bindings, each with a named latest-value panel. Task risk presentation may use
HIGH_OR_UNKNOWN: full authorized stored records are filtered before pagination,
HIGH/CRITICAL records retained, missing risk records labeled UNKNOWN with null
score/time. Unknown does not imply healthy. Assignment, tariffs and confirmed
cycles remain unavailable, with explicit guidance rather than inferred facts.

## Named working screens

Schema version1 accepts optional `screens: [{id,title,widgetIds}]` in the same
definition stored by create/draft/publish/copy/rollback. Missing or empty screens
mean one Overview screen containing all widgets. Explicit definitions allow up
to8 screens, unique safe IDs and nonempty titles up to80 characters; every widget
belongs to exactly one screen. Unknown/repeated/unassigned widget references are
rejected. The24-widget and128KiB definition bounds apply across all screens.
Sections may include `screenId`; their widgets must belong to that screen.
Screen titles are work labels and confer no authorization.

Layout coordinates are local to a screen/section surface. The editor uses
12/6/1 columns for desktop/tablet/mobile and independent positions; the API
continues accepting the legacy eight-column tablet bound. One immutable
publication includes all screens; subsequent edits do not alter it. Viewer
`screenId` selects a screen in the authorized revision, with visible fallback
to the first screen for unknown IDs. Kiosk preserves this choice. Evidence
requests include only visible widgets on the active screen. Publication validation
omits `widgetIds` to check configuration/access across all screens without
reading inactive widget measurements; invalid/denied/unavailable results block
confirmation. Existing session/ownership/ETag boundaries remain applicable.

## Energy panels

`energy-period` and `peak-and-ranking` v1 are supported interval widgets using the
telemetry energy owner port. Saved query intent may include the explicit boolean
`independentMetersConfirmed` and existing comparison settings. Validation checks all
meter boundaries without measurement fanout; a denied/invalid owner response blocks
publication. Fresh meter metadata participates in the authorized cache fingerprint.
No counter/power values are added together for the same epoch: its saved registry
method is authoritative. Null/partial/estimate/zero states remain distinct.

Energy comparisons expose covered-energy absolute change and undefined percent when
the baseline is zero or unavailable, together with both periods' coverage/methods.
The Energy task template now uses verified energy/peak capabilities and requires a
reviewed physical meter boundary. Tariff cost and confirmed cycle widgets remain
separate dependencies. Source/method/gap/replacement/reset setup is available from
energy widget configuration through the owned device registry API; writes are explicit
and optimistic, while source credentials are neither read nor stored in drafts.
## Panel annotation preferences

Existing widget `presentation` JSON can retain boolean `showTitle`, `showLegend`,
`showCalculation`, `showComparison`, `showDetails` and `showAxes`. The console
uses false defaults for the first five and a true default for axes. Only explicit
booleans enable optional annotations; unknown fields are retained. These fields
change presentation, never query scope, data authorization, source calculation
or draft/publication concurrency. On-demand panel information remains available
independently of inline-detail preference, and exceptional states remain visible.

### Grid editing compatibility

The editor uses react-grid-layout for drag and resize gestures and maps its
item identifiers back to existing `widgetId,x,y,w,h` placements. Only a completed
gesture changes the active screen's selected responsive layout. Draft ETags,
autosave, Undo/Redo, all-screen publication validation and per-viewer evidence
queries retain their existing contracts. Visible Edit titles and controls are
editing chrome; they do not enable optional View annotations. No new API or
server-computed energy/telemetry values are introduced by the grid library.

The visual widget palette consumes the existing widget manifests and maps the
grid's temporary drop ID to a new persisted widget ID. Only supported types can
be inserted; schemaVersion, ETags, publication and query contracts are unchanged.
New measurement panels are unbound pending explicit equipment/metric selection.

### Custom screen grid profiles

`definition.screens[].grid` is optional and maps `desktop`, `tablet`, `mobile`
to complete integer profiles `{columns, rowHeight, gap}`. Bounds: columns 1–48,
rowHeight 16–160 CSS px, gap 0–32 CSS px. Missing profiles render with the existing
12/6/1-column, 48px-row, 12px-gap defaults. Legacy tablet placement validation
continues accepting up to 8 columns when no explicit profile exists.

Placements on a configured screen must fit that profile's columns; server
validation rejects noninteger, unknown or out-of-range settings. Settings are
stored in the existing JSON definition and immutable revisions; copy retains
them. No SQL migration or resource grant changes are involved. The editor
clamps/reflows positions when reducing columns and only changes that screen and
responsive profile. Draft ETags, all-screen validation and explicit publication
retain their contracts. Numeric panel width/height use the same column/row units.

Widget manifest sizeConstraints now allow one column and one row on every
profile. These are resize minima; the console maintains separate, useful
insertion defaults. Placement validation continues to accept 1�100 rows.

### Prediction presentation settings

The existing presentation object supports `predictionSort` (`RECENT` or
`RISK_DESC`), `showPredictionModel` and `showPredictionTime` for prediction-evidence.
Defaults preserve server ordering and omit optional model/time columns. Risk
sorting affects only returned rows and retains total/hasNext disclosure. Finite
failureRiskScore values in [0,1] are rendered as percentages only for stored
TRAINED_MODEL + FAILURE_PREDICTION rows. Confidence remains distinct from measured
accuracy; missing or non-ready values stay unavailable. Device links require a
matching device in authorized panel evidence. These are presentation settings;
query authorization, stored prediction semantics and inference are unchanged.

Stored `failureHorizonDays` / `failure_horizon_days` is mapped to the existing
`horizonDays` dashboard field when the legacy horizon field is absent. Business
tables support optional `showRecordTime` (default true) for measured/created
columns; scheduled maintenance start/end remain visible. Risk demo panels omit
record-time columns for compactness; panel information retains source evidence.
Maintenance agenda presentation also accepts showMaintenanceFlags (default true)
for suppression/exclusion columns; deviceIds resolve through authorized panel
resources and date fields render in the viewer's locale.

### Prediction model navigation

Prediction evidence exposes modelVersionId from the stored response model identity
(responseModelVersionId or equivalent direct/snake-case field), never from a
requested deployment-policy modelId. Trained rows with valid model UUIDs link to
/settings/ml?tab=models&modelId=<uuid>&family=<recorded-family>. The registry
shows only that authorized model card; missing/filtered records show an explicit
unavailable selection. Show all models clears the selection. Heuristic states
and missing/invalid identities remain noninteractive. Navigation is read-only.


## Optimized dashboard delivery (2026-10-11)

`POST /dashboards/{id}/query-progressive` accepts the same query reference and
returns `application/x-ndjson`. Every newline is a compact batch containing newly
completed panels, at one contextHash/revision/asOf. Each scope dictionary entry is
sent once. The final line is `{complete:true,contextHash}`. A terminal failure is
`{errorCode,explanation}`; interrupted streams have no completion marker. Consumers
reject changed watermarks/actors, obsolete requests and truncated streams.
Authentication/query-reference errors before delivery retain ordinary HTTP errors;
errors after delivery begins use NDJSON. Each panel completes its own scope and
owner checks before delivery. Streams use a bounded executor and a five-second
evidence deadline. Downstream work retains HTTP/SQL deadlines after browser abort.

`GET /dashboards/{id}/query-snapshots/{contextHash}/panels/{widgetId}` returns full
source evidence for that original panel. Current actor, dashboard revision access,
resource capabilities and scope fingerprint are renewed. Retention is at most30s,
32 snapshots and32MB of serialized data, with8MB maximum per snapshot. Expired or
evicted evidence returns410 `SNAPSHOT_EXPIRED`; refresh to obtain a new snapshot.
Revocation returns403 and removes retained evidence. This API never recalculates
historical sources at a different watermark.

Query/details use `Cache-Control: no-store`. Progressive responses flush ready
panel groups immediately with `X-Accel-Buffering: no`; production ingress must
preserve these flushes.

The viewer keeps same-context panels during refresh, exposes refresh state in settings without
a status row above the canvas and aborts manual/automatic reads on supersession/unmount. Actor/revision/screen/runtime
changes clear prior evidence. Before renewed definition access, empty skeletons
show layout only; an in-memory8-entry cache retains placements/grid settings and
clears on session changes. Age clocks run only inside displayed information.

Internal `POST /internal/dashboard/energy-batch` accepts `{scope,intervals}`. The
owner-read scope supplies actor/account/workspace/devices and one `time.asOf`.
Each interval has unique key, deviceIds, from/to and independentMetersConfirmed.
There are1-64 intervals and1-50 distinct authorized devices per interval. Every
half-open interval is at most31 days and ends no later than asOf. The response is
`{asOf,intervals:{key:{period,evaluatedCount,deniedBindingCount,authorizedDeviceIds}}}`.
One renewed registry scope and one raw SQL read serve the batch, with a total
500000-selected-sample budget. SQL filters account/device/canonical source/required
metric and physical/time bounds; overlapping ranges are merged before one indexed
measurement SELECT. At most10000 merged ranges bound SQL parameters. Each interval
retains its original one-day bracketing window, clipped to physical identity and
asOf. Streams normalize once per request; binary time bounds and prefix quality
totals avoid rescanning the complete sample union per cycle. Reset/gap/replacement/raw-maintenance-consumption policies are
unchanged. Invalid intervals fail the batch; denied evidence never enters an
aggregate. Cost uses one batch across current/comparison tariff segments; operations
use one batch per current/comparison period, independent of completed-cycle count.

Maintenance pages use count + page + one account-filtered authorized device lookup
for nonempty pages. Empty pages need no device lookup. Pagination, ordering and
suppression/analytics/ML flags remain unchanged.

## Vehicle map widget v1

`vehicle-map` typeVersion1, resultType POSITIONS: one fixed VEHICLE binding and
`query.sourceId` (external source identifier). No unit/reducer/fleet/comparison
or scalar visualization is supported. Evidence is `data.positions={latest,history}`.
Time inherits the dashboard, capped24h with isolated LIMIT_EXCEEDED. Runtime
filters intersect this binding. Empty scope has no position. Authorization,
contextHash, progressive results and current resource checks are unchanged.
Map queries bypass snapshot cache to expose the next sample at refresh. Draft,
copy, undo and publication retain sourceId and presentation.follow. Renderer
performs no fetch or timer; inactive screens perform no GPS reads. Map dashboards
initially refresh at5s; browser-local refresh preference survives reload.


## Vehicle map fleet query

The existing vehicle-map widget also accepts query.vehicleSources: a device UUID to external source ID object. Its FIXED binding must contain exactly the same 1-20 device IDs. This alternative excludes query.sourceId. It returns positions.fleet latest evidence for the permitted runtime scope, with per-device observation timestamps and freshness. The existing single-device sourceId/trail contract is unchanged. Fleet queries bypass cache and preserve current ownership and source binding authorization. Shared definitions hide unauthorized source identifiers. The widget renders all positions on one retained map; individual history pages provide recorded trails/playback.


### Fleet recorded GPS tracks

The internal dashboard fleet position evidence additionally returns tracks: one PositionHistory per authorized vehicle. The interval is the intersection of the dashboard frame and the final 15 minutes ending at min(asOf,to). Existing 1000-point/history truncation bounds apply per vehicle. All device/source validation precedes these reads. The common map renders colored observed segments and pale straight connectors between discontinuous samples; connectors are visual uncertainty indications and are not stored as GPS observations. Show tracks is enabled by default.


## Active bus dashboard and configured state queries

Active dashboard quota defaults to ten per owner. Bounded owner overrides (1..1000) are stored in dashboard_owner_quotas and checked under the existing owner transaction lock for create/restore/clone. Operators may raise a specific owner without changing other owners. Dashboard metric keys accept safe dotted configured fields; telemetry discovers requested units only from measurements of allowed devices/account. Dashboard boolean series include bounded MEASURED_STATES points up to 1000 observations, otherwise observed min/max envelopes. Boolean points keep original timestamps and raw types; rendering uses step states and preserves gaps. LAST_1_HOUR is a rolling relative query period exposed in widget and runtime settings.

## Read-only AI template preparation and creation receipts

GET /dashboard-templates resolves the existing account without provisioning it.
POST /dashboard-templates/{id}/prepare accepts version, name, deviceIds, period,
optional metricKeys and sourceIds. It returns READY with a canonical definition
and digest or NEEDS_INPUT with readable diagnostics; it writes no dashboard,
revision, preference or account. Limits: 1-20 distinct owned devices, 12 widgets,
100 metric selections, 20 source selections, name length80. Unknown fields422.
Every device is authorized before labels are read; mixed or foreign bindings403.
Missing metric/unit or ambiguous registered sources require clarification. Data
absence in a selected window remains no-data, distinct from unsupported binding.
Energy/tariff/cycle configuration uses the ordinary editor in this first version.

GET /dashboards/creation-receipt requires Idempotency-Key and the current JWT.
It performs a read-only, current owner/account scoped lookup of an unexpired
POST /dashboards receipt and returns the original dashboard metadata. Missing or
foreign receipts404; lookup does not extend the existing 24-hour retention or
create resources. Current resource access is checked on replay. AI uses this
lookup after an uncertain response or restart, preserving the original private
unpublished draft. Normal widget reads still renew binding authorization.

## Conversational panel layout and unsaved preview

`POST /dashboard-templates/{id}/prepare` additionally accepts optional `panelLayout`:
an ordered list of `{key,width,height}` entries. Keys must be distinct existing
template panel keys; width is FULL/HALF/THIRD, height an integer 2–12 (defaults
FULL/4). The complete list selects panels; omitted keys are removed. Omission of
the list retains template defaults. The owning assembler packs 12/6/1-column
responsive layouts left-to-right, wrapping below the tallest panel in the row.
It includes every placement in the canonical digest. Contradicting metricKeys,
unknown keys/fields, invalid dimensions or unsupported widths fail preparation.
Current template/source/resource checks apply again before creation.

`POST /dashboards/preview/query` accepts `{definition}` with the ordinary
DashboardDefinition schema and current JWT. It validates definition, typed widget
configuration and binding authorization, then invokes the same scope/time/owner-read
and evidence projection pipeline as saved queries. It creates no dashboard, draft,
revision, receipt or preference. Unknown fields, unsafe definitions and inaccessible
bindings use the existing 422/403 contract. Responses are no-store. The returned
dashboardId is an ephemeral definition-scoped identifier, not a saved resource.
Preview evidence contains ordinary panel data without a saved-snapshot detail
link. Each preview renews access; absent observations remain no-data.

The UI renders this exact definition using DashboardCanvas and DashboardWidgetPanel.
Desktop/tablet/mobile controls select an existing prepared layout without mutating
it. Normal viewing uses the smaller of viewport and canvas width to choose the
responsive profile. Readings and freshness can change after creation; the reviewed
panel structure, queries, bindings and placements stay identical. See
[AI dashboard intent v2](ai-agent-api.md#conversational-layout-intent-v2).

## AI widget composition (2026-10-22)

Authenticated read-only `GET /dashboards/composition/widget-types` returns supported
widget types, versions, reducers and compatible visualizations from the real catalogue.
`POST /dashboards/composition/prepare` takes `{name,period,deviceIds,widgets}` and writes
no dashboard, receipt, preference or publication. The complete ordered widgets list
contains1-12 items: `{key,type,title,deviceIds?,metric?,unit?,sourceId?,visualization?,
reducer?,text?,width?,height?,period?}`. Names are bounded to80 characters, titles120,
keys60 ASCII characters; device selections contain1-20 distinct authorized IDs.
Width is FULL/HALF/THIRD and height is an integer2-12. Unknown fields/types, duplicate
keys, fractional dimensions and raw queries are rejected. Device authorization is
checked for the entire selection before reading capabilities. Metrics/units must
exist on every bound measured device; latest values bind one device.

The service compiles normal queries, fixed bindings and desktop/tablet/mobile layouts
with12/6/1 columns. The response is READY with canonical digest and exact definition,
or NEEDS_INPUT with diagnostics and no alternative definition. `vehicle-map` uses
registered source identities and VEHICLE equipment; ambiguous sources require selection.
Maps and charts can coexist without any task template. Map history is bounded to24h;
per-widget relative periods can differ explicitly. Source-bound variables, energy,
tariffs and confirmed cycles retain ordinary editor/query configuration requirements.
Prepared definitions use the existing `/dashboards/preview/query`, ordinary renderer
and ordinary private draft creation. The same owning preparation is repeated before
AI confirmation executes; digest drift fails closed. Legacy template preparation
and already-reviewed template tasks keep their existing contracts.
### Vehicle field widgets and presentation
`vehicle-value` and `vehicle-timeline` use the authorized position evidence pipeline.
Both require FIXED binding to one VEHICLE, explicit `query.sourceId`, and a recorded
`presentation.vehicleField` key such as `speedKmh`, `transport.doorsOpen` or
`transport.nextStopId`. The interval is at most24h. Value widgets show observed
number/text/boolean values, date stale observations and keep absent fields unavailable.
Timeline widgets list timestamped observations without inferring state between samples.
Editor settings `presentation.trueLabel/falseLabel` customize boolean labels.

Boolean `presentation.showTitle` controls all viewer/preview titles. Editing retains
an accessible drag header. Map boolean `presentation.showMapDetails` (omitted retains
legacy visibility) hides normal follow, recorded summary and GPS/journey disclosure
or fleet table; errors and stale/truncated warnings remain visible.
Composition accepts optional `showTitle`, `showMapDetails`, `vehicleField` per widget,
freezes them in the reviewed definition/digest and preserves them across refinement.
New AI maps default to hidden title/metadata. Charts use measured pixel dimensions
for readable axes and markers across panel sizes.


## Compact value presentation
Composition panel heights accept integers1–12, consistent with the editor's
manifest minimum. Optional boolean `compact` is stored as `presentation.compact`,
validated in both composition and definition writes and included in preparation
digests. It does not change queries, bindings or source freshness semantics.
The shared canvas automatically compacts vehicle-value/latest-value/metric-summary
cards of at most two rows unless `compact:false` is explicit. The appearance
selector supports Automatic/Compact/Full. One-row cards use reduced title/value
fonts and spacing; stale vehicle values retain a visible short warning and a
measurement-time tooltip. Full provenance remains in panel information.


## Map footer visibility
Optional boolean showMapNotices in composition is stored in presentation and
included in the definition digest. Definition and composition endpoints reject
non-boolean values. Missing/true shows stale-position and truncated-history notes;
false hides those two footer paragraphs independently of showMapDetails. Editor
Appearance exposes this setting. Errors, missing GPS state and map attribution
remain visible. Full evidence and measurement time remain in panel information.


## Recorded vehicle field graph
vehicle-timeline accepts presentation.visualization=list (default) or line. Both
modes use the ordinary authorized POSITIONS evidence for vehicleField; line mode
plots numeric/boolean observations only, retains exact timestamps, and breaks
connections for missing samples or intervals longer than30 seconds. It does not
extrapolate before/after recorded points. Text fields remain available in list
mode. Chart axes use actual pixels; boolean states retain open/closed labels.
Composition accepts visualization=line with vehicleField and without numeric
metric/unit/reducer parameters; preparation digests include this presentation.


## Shared widget capabilities and settings (2026-10-23)
`GET /dashboard-widget-types` (ordinary manifest catalogue) and
`GET /dashboards/composition/widget-types` expose the same `capabilities` descriptor.
Version 1 contains `dataKind`, accepted `valueTypes`, binding modes/device limit and
source/metric requirements, compatible `visualizations`, `visualizationValueTypes`,
and typed `settings`. Vehicle history `line` accepts number/boolean; `list` also
accepts string observations. Renderer availability and data availability remain
separate: missing observations do not become fabricated values.

Each setting has key, type, section, group, label, defaultValue, choices and maxLength.
Types are BOOLEAN, AUTO_BOOLEAN (true/false, omitted for automatic), TEXT, SELECT and
FIELD_SET (bounded recorded field keys for map tooltip selection). Sections are
GENERAL and APPEARANCE. General always contains showTitle and showDetails; the editor
also places the widget name there. Appearance is grouped by supported features,
not a shared list of switches with no effect. Data, calculation/time, navigation
and layout stay separate. Context notes do not offer unused binding/time controls.

Stored settings retain their existing presentation keys. Omitted showTitle remains
hidden in viewing for backwards compatibility; creating a composition may explicitly
choose visible titles. Editor drag headers remain visible when viewing titles are
hidden. The accessible widget name remains available. showDetails defaults false;
map follow/showMapDetails/showMapNotices default true when omitted. Compact defaults
to automatic and retains measured/stale status semantics.

Composition panels additionally accept `settings:{key:value}`. The service validates
supported keys, scalar types, enum choices, label lengths and field sets from the
same catalogue, then writes canonical presentation. A setting cannot also be supplied
as a flattened legacy showTitle/showMapDetails/showMapNotices/compact property.
Those legacy fields and existing saved presentation remain compatible. Null resets
a supported setting to its catalogue default, or clears an obsolete setting after
a type change. Unknown non-null settings are rejected. Existing legacy presentation
is retained rather than migrated or stripped during editing.

## Resolved dashboard data choices

Authenticated POST `/dashboards/composition/capabilities` is a read-only, no-store
metadata operation. Request: `{deviceIds:[UUID],period:"LAST_1_HOUR",purpose:"HISTORY"}`.
Select 1–20 distinct devices. `period` accepts the ordinary relative periods;
`purpose` is optional CURRENT/HISTORY/MAP/DOMAIN. No raw observations or connector
configuration are returned. Resource access is checked before metadata reads.

The response contains descriptorVersion, snapshotId, choices and diagnostics.
A choice identifies the actual device, registered source/external source identity,
field or registered metric, declared type/unit, and descriptor-derived views.
Each view includes widgetType/typeVersion, purpose, visualizations, reducers,
readiness and additional requirements. Domain evidence requires ordinary semantic
configuration; a numeric measurement does not establish energy/counter/tariff facts.
Operator notes have no measured-data choice. Text recorded history offers list,
while number/boolean history can offer list and line. Source views retain their
existing maximum 24-hour period. Unsupported units/views are not fabricated.

Current metadata observation presence, source freshness and history availability
are separate. Zero/false count as observations; a stale source remains STALE.
History status is UNKNOWN unless actual interval evidence establishes more; latest
metadata alone never proves history samples or a physical door transition. An empty
query interval does not delete a known field capability. This endpoint does not
return a human stop name when only an identifier is recorded.

Exact same-field/same-label choices on multiple sources retain separate identities
and an AMBIGUOUS_SOURCE diagnostic. Preparation without a unique source fails;
choosing the first source is not resolution. The resolver is request-scoped with no
cross-user cache. The response is bounded to 200 choices; excess metadata returns a
narrowing error rather than truncation. Bad payloads400, unauthenticated401,
unavailable selected resources404, semantic/limit errors422, dependency/invalid
upstream metadata503. Existing widget catalogue APIs remain available.

Composition preparation repeats current authorization and compatibility checks,
including field type and visualization, before ordinary definition validation.
Metadata drift can invalidate an older choice. Saved reviewed definitions and
execution digests retain their existing contracts.
Composition panels may include optional dataChoiceId from a resolved choice.
Preparation verifies the exact stable identity, including declared type and unit,
in addition to the supported view. The AI compiler supplies this identity from its
scoped mapping; model-authored dataChoiceId is not accepted. Legacy callers may
omit it; existing saved review digests remain unchanged.

## Reviewed draft update receipt — 2026-10-28

`PUT /dashboards/{id}/draft` accepts an optional safe `Idempotency-Key`.
Without it the ordinary editor contract is unchanged. With it, the owning draft
use case stores its response atomically through `DashboardIdempotency`, scoped
to actor/account and this draft route. Initial execution still requires the
current quoted `If-Match` draft etag, current `baseRevision`, authorized bindings
and a valid definition. A repeated key/body returns the original draft receipt;
a changed body under that key or a new key with stale version fails with409.
Current EDIT access and binding authorization are checked again on replay.

`GET /dashboards/{id}/draft/receipt` requires the same `Idempotency-Key` and
current actor authorization. It returns the immutable accepted save response,
not an assertion that no subsequent edit occurred. A missing receipt is404.
This shares the existing domain idempotency store/retention; no second store or
migration is added. Saved timestamps use PostgreSQL microsecond precision so
the first response, receipt and fresh draft read agree. Draft updates do not
create a copy, publish, change preferences or delete anything.

## Widget catalogue parameter contracts

The authenticated GET /dashboards/composition/widget-types catalogue is additive:
each supported widget includes type, typeVersion, title, description, reducers,
units, timeBasis, maxSeries, maxRows, maxDetailDevices, sizeConstraints,
visualizations and capabilities. Capability settings retain
key/type/section/group/label/defaultValue/choices/maxLength. The additive
capabilities.settingsSchema describes their actual nullable JSON values and
constraints, generated from the owning validator's setting definitions. For
AUTO_BOOLEAN, automatic is null and explicit choices are booleans, never strings
"auto", "true" or "false". Historical unknown null setting keys remain ignorable;
unsupported non-null keys are rejected. No retrieval score can expand these
contracts or data access. Existing catalogue clients can ignore new fields.