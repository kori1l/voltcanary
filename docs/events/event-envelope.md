# Event Envelope

All internal events use the same envelope shape. The envelope separates event
metadata from the domain payload and gives every consumer the fields needed for
idempotency, tracing, versioning, and troubleshooting.

## JSON Shape

```json
{
  "eventId": "uuid",
  "eventType": "TelemetryReceived",
  "eventVersion": 1,
  "occurredAt": "2026-05-12T12:30:01Z",
  "correlationId": "uuid",
  "producer": "ingestion-service",
  "accountId": "uuid",
  "payload": {}
}
```

## Fields

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `eventId` | UUID string | yes | Globally unique event identifier. Used for idempotency and audit. |
| `eventType` | string | yes | Logical event name, for example `TelemetryReceived`. |
| `eventVersion` | integer | yes | Schema version of this event type. Starts at `1`. |
| `occurredAt` | ISO-8601 instant | yes | Time when the domain event occurred, not when it was consumed. |
| `correlationId` | string | yes | Request or workflow correlation ID propagated across services. |
| `producer` | string | yes | Service that produced the event. |
| `accountId` | UUID string | when available | Tenant/account context for authorization, replay, and troubleshooting. |
| `payload` | object | yes | Event-specific payload. |

## Transitional Direct-Record Shape

Some existing topics still publish domain records directly instead of wrapping
them in `EventEnvelope<T>` as the Kafka value. During this transition, every
published common event record must expose the same metadata fields at the top
level:

- `eventId`
- `eventVersion`
- `correlationId`
- `producer`
- `accountId`
- `occurredAt`

The direct-record shape keeps current consumers and serializers compatible while
still giving consumers the envelope metadata needed for idempotency, tracing,
tenant-aware replay, and DLQ tooling. New topics should prefer the explicit
`EventEnvelope<T>` value shape.

## Example: TelemetryReceived

```json
{
  "eventId": "0b4ef3b2-833b-4a7a-9d6f-b0477cf90442",
  "eventType": "TelemetryReceived",
  "eventVersion": 1,
  "occurredAt": "2026-05-12T12:30:01Z",
  "correlationId": "2a0560b2-2a19-49aa-9e4d-784703f2587d",
  "producer": "ingestion-service",
  "accountId": "6ee1ef72-9352-4b62-8cd3-8dd06f3612f0",
  "payload": {
    "deviceId": "7f3f1f9f-8a9a-45f3-8e8a-f0d50cf69e91",
    "sourceId": "plug-001",
    "timestamp": "2026-05-12T12:30:00Z",
    "metrics": {
      "activePowerW": 840.2,
      "voltageV": 230.1,
      "currentA": 3.65,
      "energyWh": 12450.7,
      "relayState": "ON",
      "rssi": -61
    }
  }
}
```

## Topic Naming

Topic names follow:

```text
<domain>.<event-name>.v<major-version>
```

Examples:

- `telemetry.raw.v1`
- `telemetry.validated.v1`
- `anomaly.detected.v1`
- `risk-score.changed.v1`
- `alert.created.v1`

## Kafka Keys

Use stable domain keys to preserve useful ordering:

| Topic | Key |
| --- | --- |
| `telemetry.raw.v1` | `deviceId` |
| `telemetry.validated.v1` | `deviceId` |
| `anomaly.detected.v1` | `deviceId` |
| `risk-score.changed.v1` | `deviceId` |
| `alert.created.v1` | `alertId` or `deviceId` depending on consumer need |

## Versioning Rules

Backward-compatible changes can keep the same `eventVersion`:

- adding optional fields
- adding enum values when consumers tolerate unknown values
- widening payload with optional metadata

Breaking changes require a new event version and usually a new topic:

- removing fields
- renaming fields
- changing field type
- changing required semantics

## Reliability Rules

1. Every produced event must have a unique `eventId`.
2. Consumers must treat `eventId` as an idempotency key where the side effect is important.
3. Consumers must tolerate duplicate delivery.
4. Consumers must tolerate late or out-of-order telemetry.
5. Poison messages should be moved to a DLQ topic with the original envelope preserved.
6. Logs must include `eventId`, `correlationId`, `producer`, and topic where possible.
