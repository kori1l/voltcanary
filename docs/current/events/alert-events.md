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

Provider delivery is not modeled as a Kafka command in the current
implementation. Failed delivery remains in the alert database for
retry/inspection.

Target Kafka lifecycle topics are documented in
[../../target/alert-lifecycle-events.md](../../target/alert-lifecycle-events.md).
