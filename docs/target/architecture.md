# Target Architecture

This document states the target direction for the platform. It is a map, not a
replacement for the topic-specific current contracts under `docs/current`.

## How To Use This Document

Use this file to answer "where is VoltCanary going?" It is not the source of
truth for what runs today. Each section below separates the current baseline
from target gaps.

| Question | Go to |
| --- | --- |
| What runs today? | [../current/architecture/01-system-overview.md](../current/architecture/01-system-overview.md) |
| Which service owns this data? | [../current/architecture/02-service-boundaries.md](../current/architecture/02-service-boundaries.md) |
| How does telemetry move? | [../current/architecture/03-data-flow.md](../current/architecture/03-data-flow.md) and [../current/architecture/telemetry-ingestion.md](../current/architecture/telemetry-ingestion.md) |
| Which APIs exist? | [../current/api/README.md](../current/api/README.md) |
| Which Kafka topics exist? | [../current/events/README.md](../current/events/README.md) |
| How do we operate it locally? | [../current/operations/README.md](../current/operations/README.md) |
| How do we verify it? | [../current/flows/README.md](../current/flows/README.md) |

## Current Baseline

The current implementation already has:

- microservice boundaries for gateway, identity, device, ingestion, telemetry,
  analytics, alert, simulator, AI-agent, ML runtime, and frontend;
- unified ingestion with HTTP push/import, MQTT, HTTP polling, Modbus TCP, and
  OPC UA adapter families;
- compacted `source.runtime-config.v1` for ingestion source read models;
- telemetry persistence in TimescaleDB and validated telemetry events;
- analytics-owned ML lifecycle metadata with private `ml-service` fitting and
  inference runtime;
- device-owned maintenance windows/outcomes projected into analytics and alert
  services;
- local Docker Compose and Docker Desktop Kubernetes workflows.

## Target Gaps

The target architecture is not complete until these gaps are closed:

- immutable production images and release process;
- external secret management, workload identity or mTLS, and network policies;
- managed or clustered PostgreSQL, TimescaleDB, Redis, Redpanda, and object
  storage;
- production ingress/TLS, observability, SLOs, alert rules, backup/restore, and
  runbooks;
- explicit product/workspace/tenant administration, onboarding, metering,
  billing, support operations, public API keys, and enterprise controls;
- optional Kafka alert lifecycle topics if a downstream projection or separate
  notification service needs them.
- user-created dashboards with versioned definitions, private drafts, typed
  widget queries, full-scope aggregates and independent resource authorization;
  team sharing depends on workspace membership. See the concrete
  [dashboard requirements](custom-dashboards.md).

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

- [../current/architecture/telemetry-ingestion.md](../current/architecture/telemetry-ingestion.md)
- [../current/api/ingestion-api.md](../current/api/ingestion-api.md)
- [../current/events/telemetry-events.md](../current/events/telemetry-events.md)
- [../current/operations/ingestion-connectors.md](../current/operations/ingestion-connectors.md)

## Data And Consistency Target

Each service owns its database. Cross-service data appears through APIs,
events, or local read models:

- device/source/maintenance source of truth stays in `device-service`;
- telemetry source of truth stays in `telemetry-service` and TimescaleDB;
- analytics source of truth stays in `analytics-service`;
- alert lifecycle source of truth stays in `alert-service`;
- ML lifecycle metadata and deployment policy stay in `analytics-service`;
- ML fitting/inference runtime and artifact access stay in `ml-service`;
- user/session lifecycle stays in `identity-service`.

The target consistency model is explicit eventual consistency, not hidden
distributed transactions. Outbox, inbox, replay, DLQ, reconciliation, and
operator runbooks must be present for user-visible workflows that cross service
boundaries.

Owning docs:

- [../current/architecture/05-storage-design.md](../current/architecture/05-storage-design.md)
- [../current/architecture/10-consistency-contracts.md](../current/architecture/10-consistency-contracts.md)
- [../current/events/event-envelope.md](../current/events/event-envelope.md)
- [../current/events/README.md](../current/events/README.md)

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
`docs/current/architecture/09-horizontal-scaling-matrix.md`.

Owning docs:

- [../current/architecture/06-kubernetes-deployment.md](../current/architecture/06-kubernetes-deployment.md)
- [../current/architecture/09-horizontal-scaling-matrix.md](../current/architecture/09-horizontal-scaling-matrix.md)
- [../current/operations/k8s-local.md](../current/operations/k8s-local.md)
- [../current/operations/ingestion-connectors.md](../current/operations/ingestion-connectors.md)
- [production-readiness.md](production-readiness.md)

## ML Target

ML behavior should stay explainable and operationally honest:

- predictions are failure or corrective-maintenance risk within a documented
  horizon, not exact diagnosis;
- fallback, heuristic, model, and unavailable states are visible to callers;
- datasets, labels, feature snapshots, model cards, artifact hashes, and
  deployment policies are traceable;
- analytics remains resilient when ML is unavailable.

Owning docs and specs:

- [../current/architecture/11-ml-dataset-workbench.md](../current/architecture/11-ml-dataset-workbench.md)
- [ml-dataset-workbench.md](ml-dataset-workbench.md)
- [../current/api/ml-api.md](../current/api/ml-api.md)
- [../current/operations/ml-operations.md](../current/operations/ml-operations.md)
- [../current/flows/ml-full-verification.md](../current/flows/ml-full-verification.md)

## Product Target

The next durable product direction is a managed pilot, not open self-service
SaaS. Before self-service or enterprise positioning, the platform needs
explicit design and implementation work for workspace/tenant administration,
onboarding, usage metering and entitlements, production deployment hardening,
support operations, public API keys, billing, customer-facing reports, and
enterprise security controls.

Do not describe these capabilities as current behavior until code implements
their product behavior, API/data contracts, and security boundaries.
