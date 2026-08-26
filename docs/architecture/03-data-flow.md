# Data Flow

VoltCanary separates the telemetry data plane from the device/source control
plane. Device ownership, source metadata, source tokens, connector mappings, and
maintenance policy are owned by `device-service`; high-volume telemetry enters
through `ingestion-service` and then moves asynchronously through Kafka.

## Telemetry Flow

```text
HTTP / MQTT / Import / HTTP polling / Modbus TCP / OPC UA / Simulator
        |
        v
ingestion-service adapter boundary
        |
        v
IncomingTelemetryMessage
        |
        v
common ingestion pipeline
        |
        v
telemetry.raw.v1
        |
        v
telemetry-service
        |
        v
TimescaleDB + telemetry.validated.v1
        |
        v
analytics-service <-> ml-service
        |
        v
anomaly.detected.v1 / risk-score.changed.v1 / recommendation events
        |
        v
alert-service
        |
        v
frontend
```

Ingestion success means the reading has passed the ingestion boundary and has
been accepted for Kafka publication. It does not mean telemetry persistence,
analytics, or alert generation has already completed.

## Source Runtime Config Flow

`device-service` is the control-plane owner for data sources. It stores the
source record, protocol/transport/adapter metadata, token hash, versioned
adapter configuration, mapping, credential references, status, and home region.

When source configuration changes, `device-service` publishes:

```text
source.runtime-config.v1
key = dataSourceId
```

Every `ingestion-service` replica consumes this compacted topic with a
replica-specific group and rebuilds a local source read model. The normal
telemetry path resolves source status, device/account identity, adapter type,
mapping, config version, and token hash locally. New or changed sources may fail
closed until the compacted event reaches the local ingestion replica.

## Adapter Boundary

All protocol-specific paths normalize into the same canonical model:

- HTTP push and imports route through `HttpTelemetryAdapter`.
- MQTT route ownership stays in `MqttIngress`, while topic and payload mapping
  stay in `MqttTelemetryAdapter`.
- HTTP polling runs as a polling adapter owned by connector runtime leases.
- Modbus TCP runs as a polling adapter; register details stay inside the
  Modbus adapter package.
- OPC UA runs as a subscription adapter; node/session/security details stay
  inside the OPC UA adapter package.

The common ingestion pipeline does not parse MQTT topics, Modbus registers, OPC
UA NodeIds, vendor payloads, or connector-specific session objects.

## Ingestion Pipeline

For each canonical message, the common pipeline:

1. Resolves the source from the local runtime-config read model.
2. Verifies source token hash, source status, device/account association,
   adapter/protocol/transport compatibility, and import mode.
3. Validates timestamps, metric names, values, source lengths, and batch size.
4. Applies rate limits by source, device, account, and client identity.
5. Applies idempotency.
6. Builds `TelemetryReceivedEvent`.
7. Publishes `telemetry.raw.v1` with `deviceId` as the Kafka key.

In multi-replica mode, rate limiting, idempotency, and connector leases use
Redis. Local in-memory implementations are single-instance development
behavior only.

## Telemetry Persistence

`telemetry-service` consumes `telemetry.raw.v1`, defensively validates the event,
stores measurements and quality flags in TimescaleDB, and publishes
`telemetry.validated.v1` through a durable outbox.

Quality state distinguishes duplicate, out-of-order, missing-field,
invalid-value, late-arrival, and unsupported-metric cases. Telemetry reads,
latest readings, metrics, quality summaries, and exports are served from
TimescaleDB through `telemetry-service`; ingestion never owns long-term
time-series storage.

## Analytics And ML

`analytics-service` consumes validated telemetry and calculates baselines,
feature snapshots, anomalies, risk scores, recommendations, predictions, and
recalculation jobs. It calls `ml-service` for inference and lifecycle
operations where model-backed behavior is enabled.

The ML service owns dataset previews, labels, feature snapshots, training runs,
model registry records, artifact health, deployment policies, and inference
contracts. Analytics must remain resilient when ML is unavailable and use
documented fallback behavior instead of blocking telemetry ingestion.

## Alerts And Maintenance Windows

`device-service` publishes `maintenance-window.changed.v1`. Analytics and alert
services maintain local maintenance read models:

- analytics excludes matching samples from derived calculations when policy
  requires it;
- alert-service suppresses or auto-resolves matching alerts when policy
  requires it.

`alert-service` consumes analytics events, deduplicates by device/type/severity
and time window, owns alert lifecycle transitions, and owns notification
channels, preferences, and attempts.

## Failure Handling

VoltCanary assumes at-least-once delivery:

- events carry stable ids, producer metadata, correlation ids, and occurred
  times;
- consumers are idempotent through processed-event or inbox tables;
- outbox workers use row leases and replay stuck rows;
- invalid events go to topic-specific DLQs where implemented;
- Kafka publish failures in ingestion return `503` and release idempotency so
  collectors can retry;
- Redis failures in multi-replica ingestion fail closed instead of silently
  switching to local correctness state.
