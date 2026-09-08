# Consistency Contracts

This document defines the user-visible consistency behavior for the current
VoltCanary flows. The system uses synchronous request-path validation where a
caller needs an immediate decision, and Kafka/outbox processing where work can
be retried or replayed safely.

## Contract Summary

| Flow | Request-path consistency | Eventual consistency |
| --- | --- | --- |
| Auth registration/login/session | Strong. Tokens and user status are written before the response. | Identity lifecycle events are replayable through the identity outbox and device account reconciliation. |
| Device, location, sensor, and source management | Strong for API reads after a successful write in device-service. | `source.runtime-config.v1` updates the ingestion-side source read model. Ingestion may reject telemetry for a new or changed source until the compacted event reaches the local instance. |
| Telemetry ingestion | `202 Accepted` means validation, local source read-model lookup, rate limit, idempotency, and raw event publication or queued publish succeeded. | Telemetry persistence, latest readings, analytics, alerts, and notifications happen asynchronously. |
| Telemetry latest/readings/quality | Strong for data already persisted by telemetry-service. | New ingestion readings can take time to appear while Kafka and telemetry consumers process them. |
| Analytics jobs/results | Job creation is strongly persisted. | Baselines, anomalies, risk, recommendations, and predictions are produced by async jobs and outbox-published analytics events. |
| Alert creation | Strong once alert-service handles an analytics event. Duplicate source events reuse the existing alert. | Delivery requests cross the internal notification port as immutable snapshots. |
| Notification delivery | Attempt creation is strongly persisted with the alert transaction after notification preferences and enabled channels are evaluated. | Provider delivery is async, leased, retried by the notification application layer, and idempotency-keyed per alert lifecycle event/channel. |

## Propagation Targets

These are operating targets, not hard real-time guarantees:

| Path | Target |
| --- | --- |
| Ingestion `202` to telemetry latest/readings | Under 10 seconds in a healthy local or single-region deployment. |
| Telemetry validated event to analytics job creation | Under 30 seconds. |
| Analytics job creation to anomaly/risk availability | Under 2 minutes for default windows and normal data volume. |
| Analytics anomaly event to alert creation | Under 30 seconds. |
| Alert creation/escalation to notification attempt creation | Same transaction as alert creation/escalation. |
| Notification attempt to provider adapter call | Under 30 seconds when provider and worker are healthy. |

## Client Retry Expectations

| Response | Client behavior |
| --- | --- |
| `202 Accepted` from ingestion | Treat the reading as accepted. Poll telemetry or dashboard views with backoff if immediate visibility is required. |
| `409 Conflict` | Refresh the resource and retry only if the user still intends the change. Do not blindly replay conflicting writes. |
| `429 Too Many Requests` | Back off at least one rate-limit window before retrying. Reuse the same telemetry payload only when it is safe for the caller. |
| `503 Service Unavailable` | Retry with exponential backoff and jitter. For ingestion publish failures, the service forgets the idempotency key so the same reading can be retried. |
| Network timeout after submitting a write | Query by stable id, external id, or idempotency key before retrying when available. |

## Replay Procedures

Outbox rows:
- `PENDING` rows are safe for workers to claim and publish.
- stale `PROCESSING` rows are safe to replay after their lease expires.
- `FAILED` rows should be inspected, then reset to `PENDING` through the
  service reconciliation loop or a controlled database operation.

Inbox rows:
- terminal `PROCESSED` rows must not be replayed.
- `FAILED` or stale `PROCESSING` inbox rows are replayable because consumers use
  source event ids and deterministic deduplication keys.

Notification attempts:
- `PENDING` and `RETRYING` rows are safe for the notification worker to claim.
- stale `PROCESSING` rows are safe to replay after their lease expires.
- provider adapters return normalized sent, disabled, retryable failure, or
  permanent failure results; the notification application layer performs state
  transitions and retry scheduling.
- webhook, email, and Telegram adapters share the canonical alert notification
  message. Email SMTP/network failures and Telegram `429`/`5xx` responses are
  retryable; invalid email config, authentication failures, and Telegram
  `400`/`401`/`403` responses are permanent failures.

Analytics jobs:
- `PENDING` jobs are claimable by workers.
- stale `RUNNING` jobs are claimable after the job lease expires.
- completed/failed jobs clear their active key, allowing a later recalculation.

DLQ records:
- inspect payload, source topic, event id, and failure reason first.
- fix schema/configuration/data issues before replay.
- replay to the original topic only when the consumer is idempotent for the
  event id or deterministic deduplication key.

## Operational Signals

Prometheus/Actuator metrics expose the signals used to monitor these contracts:

| Signal | Example metrics |
| --- | --- |
| Outbox lag/depth | `telemetry.outbox.depth`, `analytics.outbox.depth`, `*.outbox.oldest.pending.age.seconds` |
| Failed/retrying rows | `*.outbox.depth{status="FAILED"}`, `alert.notifications.attempts.depth{status="FAILED"}` |
| Stale leases | `*.outbox.stuck.processing`, `analytics.jobs.stale.running`, `alert.notifications.stuck.processing` |
| Inbox replay pressure | `alert.analytics.inbox.depth`, `alert.analytics.inbox.stuck.processing` |
| Scheduler locks | `*.scheduler.lock.acquire`, `*.scheduler.locks.active` |
| Redis health for ingestion | `voltcanary.ingestion.redis.rate_limit.*`, `voltcanary.ingestion.redis.idempotency.*` |

Recommended alerts:
- oldest pending outbox age above the propagation target for 5 minutes.
- any stale `PROCESSING` outbox, inbox, job, or notification lease for 5 minutes.
- growing `FAILED` outbox or notification rows.
- repeated scheduler lock skips when a job should be single-owner.
- Redis idempotency errors, because ingestion fails closed for idempotency.
