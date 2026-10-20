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

## Additive geographic readings

Current telemetry.raw.v1/validated metrics maps may contain paired latitude and
longitude (degrees), speedKmh and headingDeg. Simulator sends through ingestion;
telemetry validates and stores one accepted atomic position per source/timestamp.
No new topic/envelope version. Duplicates retain the first accepted point; accepted
late/backfill points remain source-specific. GPS is not automatically selected
for energy/ML features. Deploy compatible ingestion/telemetry catalogs before
enabling VEHICLE_GPS.

## Vehicle readings extension

GPS telemetry.received v1 may carry optional numeric ignition, fuelPercent, odometerKm in metrics. Topic/envelope/source/timestamp identity remains unchanged; vehicle values and coordinates project atomically after acceptance.


## Observed transport context

GPS telemetry may include metrics.transport: a bounded object with routeId (128 chars), routeShortName (64), direction (1/2), destination (200), nextStopId (128), scheduleDeviationSeconds (integer -86400..86400), doorsOpen (boolean), accelerationMs2 (-30..30 finite), scheduledStart (HH:mm), locationSource (GPS/ODO/MAN/DR/N/A). Unknown keys/types, control characters or unbounded values reject the context. A GPS coordinate pair is required. Context is retained in valid raw readings and accepted position snapshots, exposed as point.transport in latest/history/recordings/fleet reads, and excluded from numeric normalized measurements and telemetry.validated.v1 metrics. Older positions return null. Missing fields are not carried forward. Existing tenant/source authorization and observation timestamps are unchanged.


## Configured typed field extension

`telemetry.raw.v1` optionally contains `metrics.configuredFields`, a bounded
map of `{value,type,label,unit,display}` descriptors selected by source policy.
Ingestion filters before publishing. Telemetry validates descriptor types,
stores valid descriptors in raw readings and accepted GPS points, and projects
custom numeric fields into measurements. Existing numeric canonical metrics
remain the validated analytics payload; custom text/bool and configured numeric
fields are excluded from automatic validated analytics events. Old events and
unconfigured sources remain compatible. Source runtime events carry policy
snapshots in the existing mapping object; internal registry reads carry the
same mapping for cache reconciliation.


Configured boolean descriptors retain true/false in raw events. Telemetry storage projects them to unit boolean 0/1 state measurements for charts; they remain excluded from the automatic numeric validated analytics projection.
