# Event-Driven Architecture

Documents asynchronous communication, topics, producers, and consumers.

## Kafka Scaling Policy

VoltCanary treats Kafka delivery as at-least-once. Consumers must be idempotent
where side effects matter, using `eventId` or a service-specific inbox table.

Default local topic provisioning uses `KAFKA_TOPIC_PARTITIONS=3` and
`KAFKA_TOPIC_REPLICAS=1`. Production deployments should set partitions to at
least the maximum planned consumer replicas for that topic and set replicas to
match broker fault-tolerance requirements.

| Topic | Message key | Ordering boundary | Consumer group | Default listener concurrency |
| --- | --- | --- | --- | --- |
| `telemetry.raw.v1` | `deviceId` | per device | `telemetry-service` | 1 |
| `telemetry.validated.v1` | `deviceId` | per device | `analytics-service` | 1 |
| `source.runtime-config.v1` | `dataSourceId` | per source | `ingestion-service` | 1 |
| `device-registry.changed.v1` | `accountId` when account ordering is intended, otherwise resource id | per account or resource | source-registry consumers | 1 |
| `maintenance-window.changed.v1` | `deviceId` | per device | `analytics-service`, `alert-service` | 1 |
| `identity.account-lifecycle.v1` | `userId` | per user | `device-service` | 1 |
| `anomaly.detected.v1` | `deviceId` | per device | `alert-service` | 1 |
| `risk-score.changed.v1` | `deviceId` | per device | `alert-service` | 1 |

Listener concurrency must stay less than or equal to the topic partition count.
Increase partitions before increasing consumer concurrency or replica counts
beyond the current partition count. Preserve stable message keys when producing
events so ordering boundaries do not shift during replay.

Provisioned-but-not-emitted alert lifecycle topics are target-only and are
documented in
[../../target/alert-lifecycle-events.md](../../target/alert-lifecycle-events.md).
