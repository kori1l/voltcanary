# Telemetry Events

Telemetry event types and schemas.

## `telemetry.raw.v1`

Producer: `ingestion-service`.

Key: `deviceId` as a string. The key is deterministic so all raw telemetry for
one device is ordered by the same Kafka partition.

Consumers should treat delivery as at-least-once and use event ids or derived
idempotency keys before side effects.

Record:

```text
com.voltcanary.common.events.telemetry.TelemetryReceivedEvent
```

Important fields:

- `eventId`
- `deviceId`
- `sourceId`
- `timestamp`
- `metrics`
- `receivedAt`
- `eventVersion`
- `correlationId`
- `producer`
- `accountId`
- `occurredAt`

## `telemetry.validated.v1`

Producer: `telemetry-service`.

Consumer: `analytics-service`.

Key: `deviceId` as a string.

Record:

```text
com.voltcanary.common.events.telemetry.TelemetryValidatedEvent
```

Telemetry-service publishes validated events for accepted realtime raw
telemetry. Historical/backfill events are persisted with quality flags but are
not emitted as realtime validated events.

Important fields:

- `eventId`
- `deviceId`
- `sourceId`
- `timestamp`
- `metrics`
- `qualityFlags`
- `validatedAt`
- `eventVersion`
- `correlationId`
- `producer`
- `accountId`
- `occurredAt`

## `telemetry.raw.dlq.v1`

Producer: `telemetry-service`.

Consumer: none in this repository.

Key: `deviceId` when available, otherwise the generated dead-letter id.

Record:

```text
com.voltcanary.telemetry.event.TelemetryDeadLetterEvent
```

Telemetry-service writes malformed, invalid, rate-limited, or otherwise
unprocessable raw telemetry to this topic through its telemetry outbox. The
payload includes the original event object when available, a reason code,
quality flags, and `failedAt`.

## Source Runtime Config

Source runtime configuration is documented with device events:
[device-events.md](device-events.md#sourceruntime-configv1).
