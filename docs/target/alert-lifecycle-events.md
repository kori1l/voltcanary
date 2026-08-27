# Alert Lifecycle Events Target

Alert-service currently owns alert lifecycle records, event audit rows,
notification attempts, and maintenance suppression audit in its own database.
It consumes analytics and maintenance Kafka events, but it does not publish
alert lifecycle events.

The common event module contains an alert creation record at:

```text
backend/common/common-events/src/main/java/com/voltcanary/common/events/alert/AlertCreatedEvent.java
```

If downstream projections or a separate notification service need Kafka alert
lifecycle events, introduce them as versioned event contracts. Expected topic
names follow the event-envelope naming policy:

| Topic | Key | Required source |
| --- | --- | --- |
| `alert.created.v1` | `deviceId` or `alertId`, depending on consumer ordering needs | future alert-service outbox |
| `alert.acknowledged.v1` | `alertId` | future alert-service outbox |
| `alert.resolved.v1` | `alertId` | future alert-service outbox |

`alert.created.v1` and `alert.created.v1.dlq` are provisioned by local topic
initialization, but current alert-service code does not emit them.

If notification ownership moves out of alert-service, add
`notification.requested.v1` as a new versioned event contract. Until then,
notification delivery remains inside alert-service through durable
`notification_attempts` rows.
