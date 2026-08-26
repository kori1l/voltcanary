# Ingestion API

Telemetry ingestion endpoints and contracts.

## Authentication

Collectors submit the source ingestion token as `Authorization: Bearer <token>`.
Ingestion resolves source identity from its local `source.runtime-config.v1`
read model, compares only the token hash, and verifies that the token belongs to
the submitted `sourceId` and `deviceId`. The request path does not call
`device-service` for normal source validation; if the read model has not yet
seen a source, ingestion fails closed with an unknown/invalid source response.

## POST /ingestion/readings

Accepts one realtime telemetry reading.

```json
{
  "deviceId": "0c0b7053-5b17-45cb-a631-fb56ca443a8a",
  "sourceId": "plug-001",
  "timestamp": "2026-05-12T12:30:00Z",
  "metrics": {
    "activePowerW": 840.2,
    "voltageV": 230.1,
    "currentA": 3.65,
    "energyWh": 12450.7,
    "rssi": -61,
    "relayState": "ON"
  }
}
```

Response `202`:

```json
{
  "status": "accepted",
  "acceptedCount": 1,
  "duplicateCount": 0,
  "rejectedCount": 0,
  "eventId": "6db569b2-f533-4973-bdff-2456a4f1f0a4",
  "items": []
}
```

Duplicates return `202` with `status=duplicate` and `duplicateCount=1`.
Temporary Redis or broker failures return `503` and can be retried.

## POST /ingestion/readings/batch

Accepts realtime telemetry readings with partial-success semantics.

```json
{
  "readings": [
    {
      "deviceId": "0c0b7053-5b17-45cb-a631-fb56ca443a8a",
      "sourceId": "plug-001",
      "timestamp": "2026-05-12T12:30:00Z",
      "metrics": { "activePowerW": 840.2 }
    }
  ]
}
```

The response includes `acceptedCount`, `duplicateCount`, `rejectedCount`, and
per-item `status`, `eventId`, and `reason`.

## Import Endpoints

`POST /ingestion/imports/readings` and
`POST /ingestion/imports/readings/batch` accept historical imports. They use the
same request and response contracts but bypass the realtime backfill-age limit.
Collectors should use explicit import/backfill source identities so downstream
telemetry can quality-flag historical data.

## Validation

Supported numeric metrics are `activePowerW`, `voltageV`, `currentA`,
`energyWh`, and `rssi`. `relayState` is allowed as a non-numeric companion
field. Unknown metric names, non-finite values, out-of-range values, invalid
timestamps, excessive metric counts, excessive batch sizes, and oversized source
or metric names return `400`.

## MQTT Collectors

MQTT collectors publish JSON payloads with `timestamp` and `metrics` to
`voltcanary/sources/{sourceId}/telemetry`. Source resolution and validation use
the same local runtime-config read model as HTTP. Unsupported topics and
malformed payloads are rejected at the MQTT adapter boundary before the common
pipeline runs.

## HTTP Polling Sources

HTTP polling sources use `adapterType=GENERIC_HTTP_POLLING`, `protocol=HTTP`,
and `transport=HTTP` in `source.runtime-config.v1`. They are active connectors:
the connector runtime owns lease acquisition, scheduling, retry/backoff, and
health state; the adapter only executes the HTTP request and maps the response
into canonical telemetry.

Adapter config schema `v1`:

```json
{
  "method": "GET",
  "url": "https://collector.example.com/devices/plug-001/latest",
  "pollingInterval": "30s",
  "timeout": "5s",
  "headers": {
    "Accept": "application/json"
  },
  "credentialHeaders": {
    "Authorization": "apiToken"
  }
}
```

`credentialHeaders` maps outbound header names to keys in
`credentialReferences`; the referenced value is resolved by the ingestion
credential provider. Current local resolution supports `env:NAME`,
`env://NAME`, and `sys:property.name` references. Raw credentials must not be
embedded in `adapterConfig`, `mapping`, or source metadata.

Mapping schema `v1`:

```json
{
  "timestampPath": "sampledAt",
  "metrics": {
    "activePowerW": "payload.power",
    "voltageV": "payload.voltage"
  }
}
```

Paths are dot-separated JSON object paths. If `metrics` is omitted, the
response must contain a top-level `metrics` object. If `timestampPath` is
missing in the response, ingestion uses the poll receive time as `occurredAt`.
The adapter emits only mapped metrics plus bounded metadata such as endpoint
host and HTTP status; it does not publish raw response bodies to
`telemetry.raw.v1`.

HTTP non-2xx responses, invalid config, unresolved credential references,
malformed JSON, missing mapped fields, and timeout or connection failures are
reported as per-source connector failures. The connector runtime applies
backoff and keeps other sources running independently.

## Internal Adapter Boundary

External HTTP and MQTT contracts remain stable, but both ingress paths must
produce canonical `IncomingTelemetryMessage` before validation, idempotency,
rate limiting, and Kafka publication run. The canonical model distinguishes
`occurredAt` from `receivedAt` and stores adapter/protocol/transport details in
`TelemetryOrigin` plus bounded request metadata in `IngestionContext`.

HTTP endpoints route through `IngressOrchestrator` and `HttpTelemetryAdapter`.
The adapter maps request DTOs, `Authorization: Bearer <token>`,
`X-Correlation-Id`, `X-Forwarded-For`, and import mode into the canonical
message. Batch endpoints map every reading through the same adapter path before
the common pipeline applies validation, duplicate detection, rate limiting, and
event publication.

MQTT transport routes through `MqttIngress`, which owns broker connection
lifecycle and subscription callbacks. `MqttTelemetryAdapter` owns MQTT topic
resolution and payload mapping, then emits canonical `IncomingTelemetryMessage`
with `TelemetryOrigin` set to `adapterType=GENERIC_MQTT`,
`protocol=MQTT`, `transport=MQTT`, and metadata containing the MQTT topic when
available. MQTT ingress records adapter-scoped metrics for accepted messages,
unsupported topics, invalid payloads, broker reconnects, and pipeline
rejections.

HTTP polling routes through the connector runtime after the source lease is
owned locally. `HttpPollingTelemetryAdapter` registers manifest
`generic-http-polling` with polling capability and maps successful JSON
responses into `IncomingTelemetryMessage` using the source runtime mapping.
Instances that do not own the lease do not poll the endpoint for that source.

## Configuration

Important properties:

- `app.kafka.publish-enabled`
- `app.ingestion.kafka-ack-timeout`
- `app.idempotency.redis-enabled`
- `app.idempotency.ttl`
- `app.ingestion.multi-replica-mode`
- `app.ingestion.local-correctness-state-enabled`
- `app.ingestion.max-metrics-per-reading`
- `app.ingestion.max-readings-per-batch`
- `app.ingestion.max-source-id-length`
- `app.ingestion.max-metric-name-length`
- `app.ingestion.rate-limit-enabled`
- `app.ingestion.redis-rate-limit-enabled`
- `app.ingestion.rate-limit-window`
- `app.ingestion.source-requests-per-window`
- `app.ingestion.device-requests-per-window`
- `app.ingestion.account-requests-per-window`
- `app.ingestion.ip-requests-per-window`
- `app.ingestion.backpressure-enabled`
- `app.ingestion.backpressure-cooldown`
- `app.ingestion.audit-capacity`

## Scaling And Failure Policy

`app.ingestion.multi-replica-mode=true` is the production-like mode. In this
mode, ingestion requires Redis-backed idempotency and Redis-backed rate limiting
at startup. If Redis becomes unavailable during request processing, ingestion
fails closed with `503` instead of silently switching to local counters or local
duplicate detection. The `ingestionCorrectnessState` health contributor and
`voltcanary.ingestion.correctness.state` metric label the active mode as
`shared-redis` or `local-only`.

In-memory idempotency and rate-limit counters are only allowed for explicit
single-instance/local development by setting
`app.ingestion.multi-replica-mode=false`,
`app.ingestion.local-correctness-state-enabled=true`,
`app.idempotency.redis-enabled=false`, and
`app.ingestion.redis-rate-limit-enabled=false`.

Kafka publication to `telemetry.raw.v1` waits for broker acknowledgement within
`app.ingestion.kafka-ack-timeout`. Publish failures return `503`, release the
idempotency key for retry, and open the configured backpressure cooldown so
subsequent ingress is rejected instead of buffering telemetry without bounds.

Source runtime configuration is served from the local
`source.runtime-config.v1` read model. If the stream is lagging, previously
known active sources can continue ingesting, while newly created or changed
sources may fail closed until their compacted config event reaches the
ingestion instance.
