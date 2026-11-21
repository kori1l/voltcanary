# Telemetry API

The accepted observation journal also exposes the mandatory-token private
`POST /internal/observations/read` bounded generation/checkpoint read. The signed
source-facing contract and exact quality/budget/error semantics are in
[accepted observation prefix reads](configurable-native-data.md#accepted-observation-prefix-reads).
This read does not qualify sealed snapshots, upstream coverage or RULE execution.

Telemetry query and aggregation endpoints.

All `/telemetry/devices/{deviceId}/...` endpoints require read access to the
device unless telemetry authorization is disabled for local tests.

## GET /telemetry/devices/{deviceId}/latest

Returns the latest persisted metric values for the device.

## GET /telemetry/devices/{deviceId}/readings

Returns chart-oriented points for one metric.

Query parameters:

- `metric`: supported metric name, for example `activePowerW`.
- `from`: optional ISO timestamp. Defaults to `to - maxChartRange`.
- `to`: optional ISO timestamp. Defaults to now.
- `interval`: optional aggregation bucket such as `1m`, `5m`, or `1h`.

The range must not exceed `app.telemetry.validation.max-chart-range`.
Unaggregated chart responses are capped for UI use.

## GET /telemetry/devices/{deviceId}/readings/export

Returns cursor-paginated points for detailed inspection or export.

Query parameters:

- `metric`: supported metric name.
- `from`: required ISO timestamp.
- `to`: required ISO timestamp.
- `cursor`: optional ISO timestamp returned as `nextCursor`.
- `cursorSourceId`: optional companion `nextCursorSourceId` for exact continuation
  across distinct sources at the same timestamp. New clients must pass both fields.
  Legacy timestamp-only cursors retain their original behavior.
- `limit`: optional page size, capped by
  `app.telemetry.validation.max-export-page-size`.

Example response:

```json
{
  "deviceId": "0c0b7053-5b17-45cb-a631-fb56ca443a8a",
  "metric": "activePowerW",
  "points": [
    { "timestamp": "2026-05-12T12:30:00Z", "value": 840.2 }
  ],
  "nextCursor": null
}
```

## GET /telemetry/devices/{deviceId}/metrics

Returns supported numeric metric metadata that is present for the device.
Optional `from`/`to` constrain presence to that interval. If either is supplied,
the missing bound defaults as for chart reads, and the same maximum range applies.
Without bounds, the original all-history metadata behavior is preserved.
Discovery reads persisted metric names; it does not derive availability from latest.

Supported metrics:

- `activePowerW`, unit `W`
- `voltageV`, unit `V`
- `currentA`, unit `A`
- `energyWh`, unit `Wh`
- `rssi`, unit `dBm`
- `temperatureC`, unit `C`
- `vibrationMmS`, unit `mm/s`
- `runtimeHours`, unit `h`
- `powerFactor`, unit `ratio`
- `frequencyHz`, unit `Hz`

## GET /telemetry/devices/{deviceId}/quality

Returns recent quality flags. Current flags include `OK`, `MISSING_REQUIRED_FIELD`,
`INVALID_TIMESTAMP`, `OUT_OF_ORDER`, `INVALID_VALUE`, `DUPLICATE`,
`OUT_OF_RANGE`, `NORMALIZED`, `BACKFILL`, and `RATE_LIMITED`.

## Kafka Contracts

Input topic: `telemetry.raw.v1` with `TelemetryReceivedEvent`.

Required input fields:

- `eventId`
- `deviceId`
- `sourceId`
- `timestamp`
- `metrics`
- `receivedAt`

Output topic: `telemetry.validated.v1` with `TelemetryValidatedEvent` for
accepted realtime events. Backfilled events are persisted and quality-flagged
but do not publish realtime validated events.

Bad events are published to `telemetry.raw.dlq.v1` with failure metadata.

## Storage

Metric rows are stored in `telemetry.measurements` with `device_id`,
`source_id`, `recorded_at`, `metric_name`, `metric_value`, `unit`, `quality`,
`received_at`, `created_at`, and nullable `account_id`.

`recorded_at` is the device reading time. `received_at` is ingestion arrival
time. `created_at` is telemetry-service persistence time.

Raw normalized reading metadata and quality history are stored in
`telemetry_readings` and `telemetry_quality_flags`.

## GET /telemetry/devices/{deviceId}/readings/envelope (2026-10-08)

Additive bounded display endpoint, with the same device authorization and
`max-chart-range` guard. Required query: `metric`, `from`, `to`; optional `buckets`
default 200, accepted 1–250. The existing chart/export contracts remain available.

The response has `deviceId`, `metric`, representative `points` (at most four per
non-empty bucket), `rawPointCount`, `aggregationMode=MIN_MAX_ENVELOPE`, `buckets`,
`summary` and `qualityCounts`. Bucket extrema retain their measurement timestamps
(`minAt`/`maxAt`), first/last values/times, count and `gapCount`. Coincident timestamps use sourceId
ordering to select a stable first/last value. Empty time buckets
have no fabricated values. Vertical envelope bounds do not reconstruct order,
continuous curves, threshold crossing counts, or exact gap locations within buckets.

Summary count/min/max/average/p95/p99, min/max times and last value/time are SQL
reducers over the full authorized interval. They are not calculated from display
points. Empty numeric statistics/timestamps are null. Count includes separate
source records; representative points sharing a timestamp may be combined, while
bucket bounds, full reducers and raw pages preserve their values. Read queries
share a repeatable-read snapshot within one envelope response. Later page reads
are independent snapshots and can reflect concurrent ingestion.

A gap is an interval between persisted samples exceeding three times the median
positive cadence, with a minimum 60-second cutoff. Coincident readings from several
sources do not lower that cadence. This counts interior gaps, not unobserved
leading/trailing coverage or sensor sampling obligations. Quality flags are
reported with full-range counts, including BACKFILL; no point is declared good
merely because it is finite. Persisted retention and validation policies are unchanged.

Export pages additionally return sourceId/quality on each exact recorded point and
`nextCursorSourceId` beside `nextCursor`. They order by timestamp then sourceId, so
paired cursors continue without skipping tied timestamps. Page size/range/access
limits remain enforced. The console loads one 50-value page on demand and offers
download of that page, never automatically fetching the entire series.

## Dashboard owner read

Internal POST `/internal/dashboard/telemetry` requires both an internal service
token and signed viewer bearer, including when legacy authorization is disabled.
Renewed device scope qualifies account/device IDs before reading. Its typed
common-web request supports≤500 scope devices,≤50 detail devices and half-open
UTC intervals≤31 days; existing public history limits remain unchanged.
The response separates registered metric/unit capabilities, full latest evidence,
all-sample min/max/mean/p95 and comparison summaries, measurement-quality counts,
receipt-time validation flags, and≤200 observed series bins. Zeros and actual
extrema timestamps survive downsampling; incompatible units stay separate.
Raw measurements retain maintenance provenance rather than being removed by
analytics exclusions. See [dashboard-api.md](dashboard-api.md) for source/time,
coverage, read-only state and budget semantics. V007 adds an account/device/latest
index without changing stored measurements.

## Energy owner read

Internal POST `/internal/dashboard/energy` accepts `{scope: DashboardOwnerReadRequest,
independentMetersConfirmed: boolean}`. `/internal/dashboard/energy-validation`
uses the same shape and validates scope/meter boundaries without reading measurements.
Both require the internal token plus signed viewer bearer. Registry scope is renewed
before any raw read and must match the requested user/account. Meter policies and
identities come from that registry response, never caller-supplied rules.

COUNTER_DELTA uses Wh/kWh differences within stable physical/source epochs;
10000→10500Wh yields0.5kWh. A drop or declared reset/replacement excludes the bridge,
with explicit reset/coverage metadata. Only a declared rollover time/modulus permits
its wrapped delta. POWER_INTEGRAL uses elapsed-time trapezoids over qualified W/kW
records and remains an estimate. Counter boundary interpolation also carries an
estimate label. Canonical timestamps are ordered/deduplicated; conflicting duplicates,
nonfinite/negative/wrong-family values and non-OK quality form excluded barriers.

Windows are half-open UTC with qualified bracketing samples within the saved gap
tolerance. Physical/source replacement prohibits cross-epoch bracketing. Contiguous
policy changes on the same identity retain physical continuity. Excessive gaps are
excluded, not bridged. No covered interval returns null energy; a qualified constant
zero returns0. Raw consumption includes maintenance and is not analytics/ML-filtered.

Multiple devices require explicit reviewed independence. Duplicate physical identities
or known parent/ancestor overlap are rejected before reading; unresolved ancestry cannot
qualify a multiple-meter total. Invisible physical overlap cannot be inferred from
software labels. Responses expose full authorized IDs/counts, per-device epochs/methods,
kWh totals, power extrema and recorded peak times, full-scope energy/peak rankings and
location totals. Peaks are individual equipment observations, not summed fleet peaks.
Coverage is covered meter-seconds divided by expected meter-seconds; missing equipment
remains in that denominator. Totals are partial when coverage is incomplete.

Each frame is bounded to31 days and500 devices; raw reads use at most a one-day margin
around the current/comparison windows, at most500000 source rows and a three-second
owner budget. Exceeding a bound requires a narrower query rather than truncating a
total. Persisted retention is unchanged; missing retained boundary samples reduce
coverage. API results contain bounded derived evidence, not persisted raw samples in
dashboard definitions. Query profiles do not establish ingestion or storage capacity.

## Vehicle positions

- GET `/telemetry/devices/{deviceId}/positions/latest?sourceId=...&asOf=<UTC>`:
  deviceId/sourceId/asOf/freshness/nullable position. asOf defaults to server now.
- GET `/telemetry/devices/{deviceId}/positions?sourceId=...&from=<UTC>&to=<UTC>`:
  deviceId/sourceId/from/to/points/returnedCount/truncated.

A position has recordedAt/receivedAt/latitude/longitude/nullable speedKmh and
headingDeg/quality. Both reads require current device access and its external
sourceId (not the source UUID). Invalid source/range returns400; missing user401,
denied device403 and unavailable authorization503 follow current conventions.
Latest selects recordedAt<=asOf, FRESH through30s, STALE above30s, NO_DATA if absent.
History uses `(from,to]`, at most24h, newest1000 points returned in ascending time;
truncated explicitly marks omitted earlier points. Narrow the period; v1 has no
pagination, averages of coordinates or reconstructed trips.

`telemetry.positions` is written transactionally only from accepted atomic GPS
envelopes, preserving the first accepted device/source/timestamp. Invalid raw
readings do not appear on maps. Late/backfill points do not replace newer latest
positions. Timescale policy initialization applies the same180-day retention as
measurements. H2 uses the portable table/index migration.

POST `/internal/dashboard/positions` accepts `{scope,deviceId,sourceId}` where
scope is the existing signed owner-read request with exactly one device. It renews
resource access and source association and returns `{latest,history}`. Snapshot
latest is capped at min(asOf,to); the route requires bearer and internal token.

## Vehicle monitoring extension

Fleet batches, cursor recordings, owner settings and nullable vehicle fields are specified in [vehicle monitoring](../flows/vehicle-monitoring-verification.md#contracts). Monitoring settings now use versioned snapshot/review/confirm/inspection endpoints; unconditional PUT returns400. Shared manual/AI reviews preserve exact receipts and reject stale bases, fractional idleMinutes and unknown fields. Portable V027 extends V011; original positions/latest/history bounds are unchanged.


## Internal dashboard fleet positions

POST /internal/dashboard/positions accepts the existing scope/deviceId/sourceId or an alternative scope/vehicleSources UUID-to-source object (1-20 exact scope devices). Internal token and signed dashboard authorization are required. All devices and sources are validated before any reads. Fleet response is {fleet: [latestPositionEvidence]}; its timestamps are bounded by scope.time.to/asOf. Missing targets, mixed single/fleet forms or mismatched scope return 400; revoked resource access returns 403. Fleet reads retain nullable optional vehicle metrics and 30-second freshness semantics.


### Fleet recorded GPS tracks

The internal dashboard fleet position evidence additionally returns tracks: one PositionHistory per authorized vehicle. The interval is the intersection of the dashboard frame and the final 15 minutes ending at min(asOf,to). Existing 1000-point/history truncation bounds apply per vehicle. All device/source validation precedes these reads. The common map renders colored observed segments and pale straight connectors between discontinuous samples; connectors are visual uncertainty indications and are not stored as GPS observations. Show tracks is enabled by default.


## Observed transport context

GPS telemetry may include metrics.transport: a bounded object with routeId (128 chars), routeShortName (64), direction (1/2), destination (200), nextStopId (128), scheduleDeviationSeconds (integer -86400..86400), doorsOpen (boolean), accelerationMs2 (-30..30 finite), scheduledStart (HH:mm), locationSource (GPS/ODO/MAN/DR/N/A). Unknown keys/types, control characters or unbounded values reject the context. A GPS coordinate pair is required. Context is retained in valid raw readings and accepted position snapshots, exposed as point.transport in latest/history/recordings/fleet reads, and excluded from numeric normalized measurements and telemetry.validated.v1 metrics. Older positions return null. Missing fields are not carried forward. Existing tenant/source authorization and observation timestamps are unchanged.


## Typed configured fields

GET `/telemetry/devices/{deviceId}/fields?sourceId=<optional>` uses ordinary
device read authorization and returns typed stored values with key, label,
type, unit, value, display default, source ID and observation time. It reads the
latest per-source readings within the bounded latest 100 raw observations;
values are not carried forward across missing fields. Historical numeric
series remain discoverable even after storage is disabled. Configured numeric
fields use normal chart/envelope/export storage and chart discovery; configured
text fields appear in the device telemetry table; boolean fields appear in both the table and state charts. Custom numeric
fields are not automatically added to validated analytics events. GPS points
also contain `fields` descriptors from the same accepted observation, persisted
in the nullable V013 `positions.configured_fields` column.

Map widgets accept optional `presentation.mapFields: string[]`. Undefined uses
source display defaults (legacy maps retain their existing display), empty
shows no metric details. This changes presentation only. Explicit selection
can display any stored available field; it does not restore values excluded
from collection. Location geometry still requires stored coordinates.


## Boolean telemetry charts

Configured boolean fields are discoverable chart metrics (type boolean, unit boolean, bounds 0/1). Raw JSON retains true/false; measurement series project false to 0 and true to 1. The reserved boolean unit cannot be assigned to numeric fields. Boolean series are excluded from automatic numeric analytics. V014 projects previously stored valid configured boolean observations and legacy accepted transport.doorsOpen positions without changing source times or raw JSON. Rejected observations are excluded. Up to 1000 observations per range are returned as measured states and displayed with step lines, preserving state changes and gaps. Larger ranges use min/max envelopes and do not imply exact transition order. Door chart axes show Closed/Open; other boolean states show False/True, and fractional aggregate statistics show Mixed states.


## Active bus dashboard and configured state queries

Active dashboard quota defaults to ten per owner. Bounded owner overrides (1..1000) are stored in dashboard_owner_quotas and checked under the existing owner transaction lock for create/restore/clone. Operators may raise a specific owner without changing other owners. Dashboard metric keys accept safe dotted configured fields; telemetry discovers requested units only from measurements of allowed devices/account. Dashboard boolean series include bounded MEASURED_STATES points up to 1000 observations, otherwise observed min/max envelopes. Boolean points keep original timestamps and raw types; rendering uses step states and preserves gaps. LAST_1_HOUR is a rolling relative query period exposed in widget and runtime settings.
