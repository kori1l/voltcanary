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
Temporary broker failures return `503` and can be retried.

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

## Configuration

Important properties:

- `app.kafka.publish-enabled`
- `app.ingestion.kafka-ack-timeout`
- `app.idempotency.redis-enabled`
- `app.idempotency.ttl`
- `app.ingestion.max-metrics-per-reading`
- `app.ingestion.max-readings-per-batch`
- `app.ingestion.max-source-id-length`
- `app.ingestion.max-metric-name-length`
- `app.ingestion.rate-limit-enabled`
- `app.ingestion.rate-limit-window`
- `app.ingestion.source-requests-per-window`
- `app.ingestion.device-requests-per-window`
- `app.ingestion.account-requests-per-window`
- `app.ingestion.ip-requests-per-window`
- `app.ingestion.backpressure-enabled`
- `app.ingestion.backpressure-cooldown`
- `app.ingestion.audit-capacity`
