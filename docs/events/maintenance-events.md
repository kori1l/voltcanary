# Maintenance Events

Maintenance window events describe planned or operator-declared periods where
device telemetry can be excluded from analytics, ML, and alert creation.

## Topic

```text
maintenance-window.changed.v1
```

Kafka key:

```text
deviceId
```

Using `deviceId` preserves per-device ordering for maintenance changes consumed
by analytics and alert projections.

## Producer

```text
device-service
```

## Consumers

```text
analytics-service
alert-service
```

## Event Contract

Java record:

```text
com.voltcanary.common.events.device.MaintenanceWindowChangedEvent
```

JSON shape:

```json
{
  "eventId": "0b4ef3b2-833b-4a7a-9d6f-b0477cf90442",
  "eventVersion": "v1",
  "eventType": "MAINTENANCE_WINDOW_CREATED",
  "maintenanceWindowId": "7c789741-94d9-49e4-850b-191c7734b3ff",
  "accountId": "2c07eb26-e038-4565-b637-d9787c861477",
  "deviceId": "11a45242-7bf4-4f88-a71c-5969299f3bfc",
  "title": "Planned sensor replacement",
  "description": "Technician visit; telemetry can be absent.",
  "startAt": "2026-06-30T09:00:00Z",
  "endAt": "2026-06-30T11:00:00Z",
  "suppressAlerts": true,
  "excludeFromAnalytics": true,
  "excludeFromMl": true,
  "cancelledAt": null,
  "cancellationReason": null,
  "version": 1,
  "correlationId": "2a0560b2-2a19-49aa-9e4d-784703f2587d",
  "producer": "device-service",
  "actorUserId": "8e13f0b1-8d43-4978-a131-3b65f3e46c1c",
  "occurredAt": "2026-06-29T21:45:00Z"
}
```

## Event Types

```text
MAINTENANCE_WINDOW_CREATED
MAINTENANCE_WINDOW_UPDATED
MAINTENANCE_WINDOW_CANCELLED
MAINTENANCE_WINDOW_FINISHED
```

## Consumer Rules

Consumers must:

- use `eventId` for idempotency;
- upsert by `maintenanceWindowId`;
- ignore events whose `version` is older than the local projection row;
- keep cancelled windows with `cancelledAt` for audit and reconciliation;
- exclude cancelled windows from effective maintenance policy checks.

Effective policy for overlapping windows is merged with OR semantics:

```text
suppressAlerts = any(overlappingWindow.suppressAlerts)
excludeFromAnalytics = any(overlappingWindow.excludeFromAnalytics)
excludeFromMl = any(overlappingWindow.excludeFromMl)
```
