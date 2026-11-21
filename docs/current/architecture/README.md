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
| Spring AI models, context, tools and durable execution | [spring-ai-assistant.md](spring-ai-assistant.md) |
| Detailed Russian walkthrough: UI click, LLM planning, project architecture, context, embeddings, review and execution | [ai-agent-request-walkthrough.ru.md](ai-agent-request-walkthrough.ru.md) |
| VCL parser, scalar kernels, bundles and initial native owner metadata | [vcl-compiler-front-end.md](vcl-compiler-front-end.md); [native collection owner API](../api/configurable-native-data.md). Full runtime/UI/AI integration remains pending |
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

Proposed ingestion transport: [Netty multiprotocol target](../../target/ingestion-event-transport.md)
and [proposed ADR-016](../../adr/ADR-016-netty-multiprotocol-ingestion.md).
These do not describe implemented runtime behavior.

Start with [../../target/architecture.md](../../target/architecture.md). Use it
as the roadmap-level map; use the numbered docs above for current implemented
behavior.

[Device variables](device-variables.md): definitions, materialization, graph reads and threshold ownership.
