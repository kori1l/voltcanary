# Alert Events

Alert-service owns alert lifecycle records, event audit rows, notification
attempts, and maintenance suppression audit. It currently consumes analytics
and maintenance events; alert lifecycle changes are persisted in service-local
tables rather than published to Kafka.

## Consumed Topics

| Topic | Producer | Purpose |
| --- | --- | --- |
| `anomaly.detected.v1` | `analytics-service` | Create or deduplicate anomaly alerts. |
| `risk-score.changed.v1` | `analytics-service` | Create or deduplicate risk alerts. |
| `maintenance-window.changed.v1` | `device-service` | Suppress new alerts and auto-resolve overlapping unresolved alerts. |

Consumers use `processed_events`, analytics inbox rows, and maintenance
projection checkpoints for idempotency and reconciliation.

## Current Lifecycle Audit

The current implementation records lifecycle history in the `alert_events`
table for alert creation, acknowledgement, resolution, escalation, and
maintenance auto-resolution. Notification delivery is driven by durable
`notification_attempts` rows and provider idempotency keys.

Alert creation and escalation request delivery through the internal
`AlertNotificationPort` using immutable notification request snapshots that
include the alert id, alert event id, account id, device/source context,
lifecycle event type, severity/status, message text, occurred timestamp, and an
idempotency key. The notification module evaluates preferences and channels
before creating attempts. Provider adapters receive normalized delivery
commands and return normalized results; retry and attempt state transitions
remain in the notification application layer.

Provider delivery is not modeled as a Kafka command in the current
implementation. Failed delivery remains in the alert database for
retry/inspection.

Target Kafka lifecycle topics are documented in
[../../target/alert-lifecycle-events.md](../../target/alert-lifecycle-events.md).

## Listener DLQs

Alert-service does not publish alert lifecycle topics today, but its Kafka
listeners are configured with retry and dead-letter publication for failed
analytics or maintenance input records.

| Source topic | DLQ topic |
| --- | --- |
| `anomaly.detected.v1` | `anomaly.detected.dlq.v1` |
| `risk-score.changed.v1` | `risk-score.changed.dlq.v1` |
| `maintenance-window.changed.v1` | `maintenance-window.changed.dlq.v1` |

The retry policy is configured under `app.alert.kafka.retry`; the DLQ topic
names are configured under `app.alert.kafka.dlq`.
