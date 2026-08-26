# Architecture Docs

Architecture docs describe current implemented structure and target direction.
They should link to API/event contracts and OpenSpec specs instead of copying
large contract details.

## Read Order

1. [System overview](01-system-overview.md)
2. [Service boundaries](02-service-boundaries.md)
3. [Data flow](03-data-flow.md)
4. [Target architecture](12-target-architecture.md)

## Current Architecture

| Topic | Document |
| --- | --- |
| System map, services, main events | [01-system-overview.md](01-system-overview.md) |
| Service ownership and allowed interactions | [02-service-boundaries.md](02-service-boundaries.md) |
| Telemetry, source config, analytics, alert, and maintenance flows | [03-data-flow.md](03-data-flow.md) |
| Event-driven policy and Kafka topic scaling | [04-event-driven-architecture.md](04-event-driven-architecture.md) |
| Storage ownership, outbox/inbox, runtime stores | [05-storage-design.md](05-storage-design.md) |
| Kubernetes deployment model and production gaps | [06-kubernetes-deployment.md](06-kubernetes-deployment.md) |
| Observability | [07-observability.md](07-observability.md) |
| Authentication, source tokens, secrets, isolation | [08-security.md](08-security.md) |
| Service-by-service horizontal scaling status | [09-horizontal-scaling-matrix.md](09-horizontal-scaling-matrix.md) |
| User-visible consistency and replay contracts | [10-consistency-contracts.md](10-consistency-contracts.md) |
| ML dataset workbench architecture | [11-ml-dataset-workbench.md](11-ml-dataset-workbench.md) |
| Ingestion adapter and connector architecture | [telemetry-ingestion.md](telemetry-ingestion.md) |
| Alert event trust boundary | [alert-service-trust-boundary.md](alert-service-trust-boundary.md) |

## Target Architecture

Start with [12-target-architecture.md](12-target-architecture.md). It links to
the owning OpenSpec specs for ingestion, ML, frontend, deployment, and product
direction. Use it as the roadmap-level map; use the numbered docs above for
current implemented behavior.
