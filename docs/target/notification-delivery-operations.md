# Notification Delivery Operations Target

Notification delivery currently runs inside `alert-service` through an internal
notification module. The next target is to make that module easier to operate
without extracting a separate service.

Related OpenSpec change:

- `openspec/changes/archive/2026-09-08-harden-notification-delivery-operations`

## Target Capabilities

| Capability | Target behavior |
| --- | --- |
| Test delivery | Operators can send a test notification for an existing channel without creating or changing an alert. |
| Channel diagnostics | Operators can inspect adapter availability, last success/failure, retrying/failed/pending counts, oldest pending age, and last test result. |
| Retry policy | Retryable failures use bounded backoff with max delay, jitter, max attempts, next retry timestamp, and clear exhaustion state. |
| Secret handling | APIs, logs, diagnostics, frontend forms, and examples never expose provider secrets. |
| Frontend operations | Notification settings expose test send and diagnostics through focused feature modules, not route-page state. |

## Current Status

The archived `harden-notification-delivery-operations` implementation promoted
these target capabilities into the current Alert API and operator console:

- `POST /alerts/notification-channels/{id}/test`
- `GET /alerts/notification-channels/{id}/diagnostics`
- bounded retry/backoff state with next retry and exhaustion visibility
- focused frontend notification operations modules under
  `frontend/src/features/notifications`

Keep this target page only as a short pointer to the hardening capability set;
the authoritative implemented contracts live under `docs/current`.
