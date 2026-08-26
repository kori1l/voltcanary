# Horizontal Scaling Matrix

This matrix documents whether each service can safely run with more than one
replica today and which external state is required for correct behavior.

The baseline assumption is at-least-once Kafka delivery. Consumers and
publishers must tolerate duplicates, stale worker leases, and process crashes
between database commits and external side effects.

## Service Matrix

| Service | Multi-replica status | Required external state | Current blockers / notes |
| --- | --- | --- | --- |
| `api-gateway` | Conditional | Shared JWT verification keys, downstream service discovery, Redis-backed rate limiting before scaling protected/rate-limited traffic | Routing and JWT verification are stateless. The current general limiter is per gateway instance, so limits are not globally consistent until Redis-backed gateway limiting is enabled. |
| `ingestion-service` | Verified with horizontal smoke when shared state is enabled | Kafka partitions, compacted `source.runtime-config.v1` replay, Redis idempotency keys, Redis-backed rate limits, Redis connector leases | Kafka production is safe with idempotent producer settings and deterministic `deviceId` keys. Running multiple replicas requires source runtime-config consumers to use replica-specific groups so every replica builds the local read model; local in-memory fallbacks are single-instance development behavior. |
| `telemetry-service` | Safe with Kafka partitioning | Kafka consumer group, telemetry database, `processed_events`, `telemetry_event_outbox` with row leases | Consumers record processed raw event ids before side effects. Validated/DLQ events are published through a lease-based outbox, so stale `PROCESSING` rows are claimable by another worker. |
| `analytics-service` | Safe with database leases | Kafka consumer group, analytics database, `processed_events`, `analytics_jobs` leases, `analytics_event_outbox` leases, scheduler locks | Telemetry consumers are idempotent. Recalculation jobs and outbox rows are claim-based. Scheduled retention uses `scheduler_locks`. |
| `alert-service` | Safe with database leases | Kafka consumer group, alert database, `processed_events`, `scheduler_locks`, `notification_attempts` leases | Analytics event consumers are idempotent. Escalation/retention use scheduler locks. Notification delivery uses durable attempts, idempotency keys, and row leases. |
| `simulator-service` | Limited | Simulator database, simulation run leases | Run state still depends on an in-process `RunningSimulationRegistry` for active scheduled tasks. The database records stale running runs and the manager reconciles them, but multiple replicas should not be used for the same simulated device workload until run ownership is fully externalized. |

## Supporting Services

| Service | Multi-replica status | Required external state | Current blockers / notes |
| --- | --- | --- | --- |
| `identity-service` | Safe with database leases | Identity database, refresh tokens, scheduler locks, `identity_event_outbox` leases | Auth endpoints are database-backed. Refresh-token cleanup uses scheduler locks. Lifecycle propagation uses a lease-based outbox and device-service idempotent inbox handling. |
| `device-service` | Safe with database leases | Device database, `processed_events`, `device_event_outbox` leases | Registry mutations write durable outbox rows. Identity lifecycle events are applied idempotently through `processed_events`. |
| `ml-service` | Conditional | Model artifact storage, model registry database/state, consistent model loading policy | Stateless health/inference endpoints can scale when all replicas read the same model registry/artifacts. Training and model promotion need a single-owner or job-claiming policy before parallel execution. |

## Scaling Rules

- Enable multiple replicas only when all request limits, idempotency keys,
  consumer inboxes, work queues, and locks used by that service are backed by
  shared infrastructure rather than process memory.
- Services that consume Kafka must use stable consumer groups and enough topic
  partitions for the intended parallelism.
- Services that publish through outbox tables must keep outbox workers enabled
  on every replica; row leases prevent concurrent ownership of the same event.
- Process-local fallbacks are development conveniences. They should be treated
  as single-replica mode.
- Automatic replica scaling should remain disabled for services marked
  `Conditional` or `Limited` until the listed external-state requirement is
  satisfied in that environment.

## Current Recommendation

- Safe to run more than one replica after shared infrastructure is configured:
  `telemetry-service`, `analytics-service`, `alert-service`, `identity-service`,
  and `device-service`.
- Keep single-replica unless Redis-backed protections are enabled:
  `api-gateway`.
- `ingestion-service` may run horizontally only after the horizontal ingestion
  smoke passes for the target environment.
- Keep single-replica for active simulation workloads:
  `simulator-service`.
