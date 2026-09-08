# Notification Delivery Operations Target

Notification delivery currently runs inside `alert-service` through an internal
notification module. The next target is to make that module easier to operate
without extracting a separate service.

Related OpenSpec change:

- `openspec/changes/harden-notification-delivery-operations`

## Target Capabilities

| Capability | Target behavior |
| --- | --- |
| Test delivery | Operators can send a test notification for an existing channel without creating or changing an alert. |
| Channel diagnostics | Operators can inspect adapter availability, last success/failure, retrying/failed/pending counts, oldest pending age, and last test result. |
| Retry policy | Retryable failures use bounded backoff with max delay, jitter, max attempts, next retry timestamp, and clear exhaustion state. |
| Secret handling | APIs, logs, diagnostics, frontend forms, and examples never expose provider secrets. |
| Frontend operations | Notification settings expose test send and diagnostics through focused feature modules, not route-page state. |

## Current Gap

Current docs under `docs/current` describe implemented channel management,
preferences, attempts, provider adapters, leases, and fixed retry behavior.
Target-only endpoints such as channel test delivery and channel diagnostics
must not be documented as current API behavior until implemented.
