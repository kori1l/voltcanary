# Storage Design

VoltCanary uses service-owned storage. Runtime code must not join across
another service database; cross-service views are built through APIs, events,
or local read models.

## Stores

| Store | Owner | Purpose |
| --- | --- | --- |
| Identity PostgreSQL | `identity-service` | Users, roles, password/reset/email state, refresh sessions, login audit, lifecycle outbox |
| Device PostgreSQL | `device-service` | Accounts, locations, devices, sensors, data sources, source token hashes, maintenance windows, device outbox/inbox |
| Ingestion runtime state | `ingestion-service` | Local source runtime-config read model, bounded audit state, connector health snapshots |
| TimescaleDB | `telemetry-service` | Telemetry readings, measurements, quality flags, latest/query state, telemetry outbox/inbox |
| Analytics PostgreSQL | `analytics-service` | Baselines, features, anomalies, risk, predictions, recommendations, jobs, ML settings projections, maintenance read model |
| Alert PostgreSQL | `alert-service` | Alerts, rules, notification channels, notification attempts, maintenance read model, alert inbox/outbox state |
| Simulator PostgreSQL | `simulator-service` | YAML configs, config versions, simulation runs, run events |
| AI Agent PostgreSQL | `ai-agent-service` | Provider config, model config, encrypted provider secrets, plan/execution state where enabled |
| ML runtime storage | `ml-service` | Dataset metadata, feature snapshots, training runs, model registry metadata, deployment policy, artifact references |
| Object storage | `ml-service` and future exports | Model artifacts, dataset/model cards, reports, large import/export artifacts |
| Redis | Gateway, ingestion, telemetry where configured | Rate limits, idempotency, connector leases, short-lived cache-like state |
| Redpanda/Kafka | Shared event backbone | Versioned domain events, telemetry pipeline topics, compacted read-model topics |

## Ownership Rules

1. Service databases are private implementation details.
2. Public reads and commands go through the owning service API.
3. Asynchronous propagation uses versioned events and idempotent consumers.
4. Derived local read models must be rebuildable from their source event stream
   or through an explicitly documented reconciliation API.
5. Redis is not the source of truth for durable business data.
6. Object storage paths and artifact metadata must be traceable from service
   database records.

## Telemetry Storage

`telemetry-service` owns long-term telemetry persistence. Ingestion publishes
canonical raw events and does not write TimescaleDB directly.

TimescaleDB stores telemetry by device, metric, timestamp, source, and quality
state. Query APIs expose bounded ranges, latest values, metrics, quality
summaries, and export pages. Retention, compression, and aggregate policies are
deployment decisions that must be documented before production use.

## Outbox, Inbox, And Leases

Services that publish events after database mutations use outbox tables where
dual-write risk matters. Consumers record processed event ids or use inbox
tables where side effects must be idempotent.

Background workers and schedulers use database or Redis leases depending on
the ownership boundary:

- domain outbox/inbox replay uses service database leases;
- ingestion active connector ownership uses fenced Redis leases in
  multi-replica mode;
- local in-memory leases are valid only for single-instance development.

## Production Gaps

- Define production backup/restore procedures for each PostgreSQL database,
  TimescaleDB, Redis, Redpanda, and object storage.
- Replace local-only secrets and storage defaults with managed production
  equivalents.
- Define telemetry retention/compression policies per plan or environment.
- Define artifact retention and deletion behavior for datasets, models, and
  reports.
