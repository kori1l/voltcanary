# Telemetry API

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
