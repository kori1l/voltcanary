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
