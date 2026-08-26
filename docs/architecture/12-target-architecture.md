# Target Architecture

This document states the target direction for the platform. It is a map, not a
replacement for the topic-specific contracts under `docs/architecture`,
`docs/api`, `docs/events`, and `openspec/specs`.

## How To Use This Document

Use this file to answer "where is VoltCanary going?" Then follow the links in
each section for the implemented contract and the active requirement source.

| Question | Go to |
| --- | --- |
| What runs today? | [01-system-overview.md](01-system-overview.md) |
| Which service owns this data? | [02-service-boundaries.md](02-service-boundaries.md) |
| How does telemetry move? | [03-data-flow.md](03-data-flow.md) and [telemetry-ingestion.md](telemetry-ingestion.md) |
| Which APIs exist? | [../api/README.md](../api/README.md) |
| Which Kafka topics exist? | [../events/README.md](../events/README.md) |
| How do we operate it locally? | [../operations/README.md](../operations/README.md) |
| How do we verify it? | [../flows/README.md](../flows/README.md) |

## Target Shape

VoltCanary should evolve as a domain-oriented platform with a clear split
between the control plane and data plane.

```text
CONTROL PLANE
  identity-service
  device-service
  alert rules and notification configuration
  ML lifecycle and deployment policy
  source configuration and credential references

DATA PLANE
  ingestion-service adapters and connector runtime
  telemetry-service time-series persistence
  analytics-service signal calculation
  alert-service incident lifecycle
  ml-service inference runtime
```

The system may remain a monorepo and a set of independently deployable
services. Future service extraction should not change telemetry event semantics
or public contracts unless an explicit versioned change introduces that break.

## Ingestion Target

`ingestion-service` is the permanent telemetry data-plane boundary. It should
keep one canonical path:

```text
transport-specific input
  -> adapter
  -> IncomingTelemetryMessage
  -> common ingestion pipeline
  -> TelemetryReceivedEvent
  -> telemetry.raw.v1
```

Target rules:

1. Adding a protocol or vendor integration registers an adapter.
2. The common pipeline stays protocol-neutral.
3. Source runtime configuration is replicated from `device-service` through
   compacted `source.runtime-config.v1`.
4. Normal telemetry validation does not synchronously call `device-service`.
5. Active connectors are owned by connector runtime leases, not by adapters.
6. Multi-replica correctness uses shared Redis-backed idempotency, rate limits,
   and fenced connector leases.
7. `telemetry.raw.v1` remains keyed by `deviceId` unless a versioned event
   contract changes the ordering boundary.

Implemented adapter families include HTTP push/import, MQTT inbound, HTTP
polling, Modbus TCP polling, and OPC UA subscription. Future protocol work
should extend the same adapter, source config, credential reference, and
connector health contracts.

Owning docs and specs:

- [telemetry-ingestion.md](telemetry-ingestion.md)
- [../api/ingestion-api.md](../api/ingestion-api.md)
- [../events/telemetry-events.md](../events/telemetry-events.md)
- [../operations/ingestion-connectors.md](../operations/ingestion-connectors.md)
- `openspec/specs/ingestion-architecture/spec.md`
- `openspec/specs/ingestion-source-runtime-config/spec.md`
- `openspec/specs/ingestion-scaling-runtime/spec.md`
- `openspec/specs/ingestion-protocol-adapters/spec.md`

## Data And Consistency Target

Each service owns its database. Cross-service data appears through APIs,
events, or local read models:

- device/source/maintenance source of truth stays in `device-service`;
- telemetry source of truth stays in `telemetry-service` and TimescaleDB;
- analytics source of truth stays in `analytics-service`;
- alert lifecycle source of truth stays in `alert-service`;
- ML lifecycle and artifact metadata stay in `ml-service`;
- user/session lifecycle stays in `identity-service`.

The target consistency model is explicit eventual consistency, not hidden
distributed transactions. Outbox, inbox, replay, DLQ, reconciliation, and
operator runbooks must be present for user-visible workflows that cross service
boundaries.

Owning docs:

- [05-storage-design.md](05-storage-design.md)
- [10-consistency-contracts.md](10-consistency-contracts.md)
- [../events/event-envelope.md](../events/event-envelope.md)
- [../events/README.md](../events/README.md)

## Deployment Target

The local Kubernetes overlay is for development and validation. Production
deployment should use:

- immutable images built by CI;
- external secret management;
- ingress or Gateway API with TLS;
- managed or clustered PostgreSQL, TimescaleDB, Redis, Redpanda, and object
  storage;
- database migration jobs or a controlled migration strategy;
- NetworkPolicies and least-privilege service identity;
- Prometheus/Grafana/Loki or equivalent production observability;
- alert rules, SLOs, runbooks, backup/restore, and rollback procedures.

Horizontal service scaling is allowed only when required external state is
shared and verified. The current service-by-service status lives in
`docs/architecture/09-horizontal-scaling-matrix.md`.

Owning docs:

- [06-kubernetes-deployment.md](06-kubernetes-deployment.md)
- [09-horizontal-scaling-matrix.md](09-horizontal-scaling-matrix.md)
- [../operations/k8s-local.md](../operations/k8s-local.md)
- [../operations/ingestion-connectors.md](../operations/ingestion-connectors.md)

## ML Target

ML behavior should stay explainable and operationally honest:

- predictions are failure or corrective-maintenance risk within a documented
  horizon, not exact diagnosis;
- fallback, heuristic, model, and unavailable states are visible to callers;
- datasets, labels, feature snapshots, model cards, artifact hashes, and
  deployment policies are traceable;
- analytics remains resilient when ML is unavailable.

Owning docs and specs:

- [11-ml-dataset-workbench.md](11-ml-dataset-workbench.md)
- [../api/ml-api.md](../api/ml-api.md)
- [../operations/ml-operations.md](../operations/ml-operations.md)
- [../flows/ml-full-verification.md](../flows/ml-full-verification.md)
- `openspec/specs/ml-failure-prediction/spec.md`
- `openspec/specs/ml-feature-platform/spec.md`
- `openspec/specs/ml-dataset-lifecycle/spec.md`
- `openspec/specs/ml-artifact-runtime/spec.md`

## Product Target

The next durable product direction is a managed pilot, not open self-service
SaaS. Before self-service or enterprise positioning, the platform needs
explicit OpenSpec changes for workspace/tenant administration, onboarding,
usage metering and entitlements, production deployment hardening, support
operations, public API keys, billing, customer-facing reports, and enterprise
security controls.

Do not add these capabilities through informal backlog documents. Each one
changes product behavior, API/data contracts, or security boundaries and should
enter through an OpenSpec change.
