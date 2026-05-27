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

Returns supported metric metadata that is present for the device.

Supported metrics:

- `activePowerW`, unit `W`
- `voltageV`, unit `V`
- `currentA`, unit `A`
- `energyWh`, unit `Wh`
- `rssi`, unit `dBm`

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
