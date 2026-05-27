# Telemetry Service

This service can be opened in IntelliJ in two ways:

1. As part of the full backend Maven reactor:

   ```powershell
   cd D:\canary\voltcanary\backend
   mvn -pl services/telemetry-service -am compile
   ```

2. As a standalone IntelliJ project from this folder:

   ```text
   D:\canary\voltcanary\backend\services\telemetry-service
   ```

   Before opening it standalone, install the shared backend libraries into the
   local Maven repository:

   ```powershell
   .\bootstrap-standalone.ps1
   ```

## Why The Bootstrap Is Needed

`telemetry-service` depends on `common-events`:

```xml
<dependency>
    <groupId>com.voltcanary</groupId>
    <artifactId>common-events</artifactId>
    <version>${project.version}</version>
</dependency>
```

When the full backend root is opened, Maven sees `common-events` as a reactor
module. When only this folder is opened, Maven resolves `common-events` from
the local Maven repository instead. The bootstrap script installs that shared
artifact locally.

## Runtime Contract

Telemetry read endpoints under `/telemetry/devices/{deviceId}/...` require the
gateway-provided `X-User-Id` header when `app.telemetry.authorization.enabled`
is true. `ADMIN` and `SUPPORT` roles can read all telemetry; regular users are
checked through `device-service` at
`/internal/devices/{deviceId}/access?userId={userId}`.

Raw events from `telemetry.raw.v1` are validated before measurement rows are
persisted. Required fields are `deviceId`, `sourceId`, `timestamp`, and
`metrics`. The service rejects events outside the configured timestamp window,
unsupported metric names, non-finite values, and out-of-range metric values.
It also enforces `app.telemetry.validation.max-metrics-per-event` and
`app.telemetry.validation.max-events-per-device-per-minute`.
Rejected events are recorded in `telemetry_quality_flags` and published to
`telemetry.raw.dlq.v1`.

Duplicate device/source/timestamp/metric rows are ignored at the database
boundary. Duplicate events do not republish `TelemetryValidatedEvent`; they are
recorded as `DUPLICATE` quality flags.

## Metrics And Querying

Measurement rows are persisted with JDBC batch updates and fall back to
row-by-row duplicate handling only when the batch hits an idempotency conflict.
Chart reads support optional aggregation with `interval`, for example
`/telemetry/devices/{deviceId}/readings?metric=activePowerW&interval=1m`.
Chart ranges are capped by `app.telemetry.validation.max-chart-range`.
Detailed export uses
`/telemetry/devices/{deviceId}/readings/export?metric=activePowerW&from=...&to=...&limit=...`
and returns `nextCursor` for the next page.

Supported metric metadata is owned by `MetricCatalog` and exposed per device at
`GET /telemetry/devices/{deviceId}/metrics`. The current supported metrics are
`activePowerW`, `voltageV`, `currentA`, `energyWh`, and `rssi`.

The service emits Micrometer metrics for event processing:

- `voltcanary.telemetry.events`
- `voltcanary.telemetry.rows`
- `voltcanary.telemetry.processing`

Spring Kafka client metrics are exposed through actuator and Prometheus; use
the consumer lag metrics for `telemetry.raw.v1` alerts.

## TimescaleDB

`app.telemetry.timescale.policy-enabled=false` by default so H2 and plain
PostgreSQL tests remain portable. In TimescaleDB environments, enabling it
creates the `telemetry.measurements` hypertable and applies compression after
7 days plus raw retention after 180 days.

## Event Compatibility

`TelemetryReceivedEvent` is the input contract from `telemetry.raw.v1`, and
`TelemetryValidatedEvent` is the output contract to `telemetry.validated.v1`.
Changes must remain backward-compatible for existing fields; incompatible
changes require a new topic version such as `.v2`. Schema registry can be added
later if event contracts grow beyond this explicit versioning policy.

Source/device binding is trusted from `ingestion-service`: telemetry-service
revalidates event shape and metrics, but it does not call `device-service` for
every consumed event. Missing source metadata is recorded as quality/errors once
a local source registry projection exists.

## Configuration

Important telemetry validation/query properties:

- `app.telemetry.validation.max-future-skew`
- `app.telemetry.validation.max-past-age`
- `app.telemetry.validation.max-metrics-per-event`
- `app.telemetry.validation.max-events-per-device-per-minute`
- `app.telemetry.validation.max-chart-range`
- `app.telemetry.validation.max-export-page-size`
- `app.telemetry.validation.backfill-source-prefixes`

## Quality Flags

- `OK`: metric row is accepted.
- `MISSING_REQUIRED_FIELD`: event lacks required envelope fields.
- `INVALID_TIMESTAMP`: event timestamp is outside the accepted window.
- `OUT_OF_ORDER`: event is older than the latest known device timestamp.
- `INVALID_VALUE`: unsupported, non-numeric, or non-finite metric value.
- `DUPLICATE`: duplicate device/source/timestamp event.
- `OUT_OF_RANGE`: metric value is outside catalog limits.
- `NORMALIZED`: reserved for normalization notes.
- `BACKFILL`: historical import accepted without realtime publish.
- `RATE_LIMITED`: per-device event rate limit exceeded.
