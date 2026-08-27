# Architecture Docs

Architecture docs under `docs/current` describe implemented structure. Target
direction lives under `docs/target`.

## Read Order

1. [System overview](01-system-overview.md)
2. [Service boundaries](02-service-boundaries.md)
3. [Data flow](03-data-flow.md)
4. [Target architecture](../../target/architecture.md)

## Current Or Target

| Need | Read |
| --- | --- |
| What exists in code today | [01-system-overview.md](01-system-overview.md), [02-service-boundaries.md](02-service-boundaries.md), [03-data-flow.md](03-data-flow.md), [04-event-driven-architecture.md](04-event-driven-architecture.md), [05-storage-design.md](05-storage-design.md), [06-kubernetes-deployment.md](06-kubernetes-deployment.md), [09-horizontal-scaling-matrix.md](09-horizontal-scaling-matrix.md), [10-consistency-contracts.md](10-consistency-contracts.md) |
| Where the system is going | [../../target/architecture.md](../../target/architecture.md) |
| A target item that is provisioned but not emitted/used yet | Read the target document for that topic, for example [../../target/alert-lifecycle-events.md](../../target/alert-lifecycle-events.md) |

## Current Architecture

| Topic | Document |
| --- | --- |
| System map, services, main events | [01-system-overview.md](01-system-overview.md) |
| Service ownership and allowed interactions | [02-service-boundaries.md](02-service-boundaries.md) |
| Telemetry, source config, analytics, alert, and maintenance flows | [03-data-flow.md](03-data-flow.md) |
| Event-driven policy and Kafka topic scaling | [04-event-driven-architecture.md](04-event-driven-architecture.md) |
| Storage ownership, outbox/inbox, runtime stores | [05-storage-design.md](05-storage-design.md) |
| Local Kubernetes deployment model | [06-kubernetes-deployment.md](06-kubernetes-deployment.md) |
| Observability | [07-observability.md](07-observability.md) |
| Authentication, source tokens, secrets, isolation | [08-security.md](08-security.md) |
| Service-by-service horizontal scaling status | [09-horizontal-scaling-matrix.md](09-horizontal-scaling-matrix.md) |
| User-visible consistency and replay contracts | [10-consistency-contracts.md](10-consistency-contracts.md) |
| ML dataset workbench architecture | [11-ml-dataset-workbench.md](11-ml-dataset-workbench.md) |
| Ingestion adapter and connector architecture | [telemetry-ingestion.md](telemetry-ingestion.md) |
| Alert event trust boundary | [alert-service-trust-boundary.md](alert-service-trust-boundary.md) |

## Target Architecture

Start with [../../target/architecture.md](../../target/architecture.md). Use it
as the roadmap-level map; use the numbered docs above for current implemented
behavior.
