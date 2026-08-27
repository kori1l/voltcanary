# Current Implementation

This folder is the entry point for what VoltCanary runs today. Linked documents
describe behavior backed by code, migrations, frontend clients, deployment
manifests, tests, or local runbooks.

## System

| Topic | Document |
| --- | --- |
| System map, services, ports, main runtime state | [architecture/01-system-overview.md](architecture/01-system-overview.md) |
| Service ownership and allowed interactions | [architecture/02-service-boundaries.md](architecture/02-service-boundaries.md) |
| Telemetry, source config, identity lifecycle, analytics, alert, and maintenance flows | [architecture/03-data-flow.md](architecture/03-data-flow.md) |
| Kafka topics currently consumed/emitted | [architecture/04-event-driven-architecture.md](architecture/04-event-driven-architecture.md) |
| Storage ownership and runtime stores | [architecture/05-storage-design.md](architecture/05-storage-design.md) |
| Local Kubernetes deployment | [architecture/06-kubernetes-deployment.md](architecture/06-kubernetes-deployment.md) |
| Observability | [architecture/07-observability.md](architecture/07-observability.md) |
| Security | [architecture/08-security.md](architecture/08-security.md) |
| Horizontal scaling status | [architecture/09-horizontal-scaling-matrix.md](architecture/09-horizontal-scaling-matrix.md) |
| Consistency and replay contracts | [architecture/10-consistency-contracts.md](architecture/10-consistency-contracts.md) |
| ML dataset workbench | [architecture/11-ml-dataset-workbench.md](architecture/11-ml-dataset-workbench.md) |

## Contracts

| Topic | Document |
| --- | --- |
| HTTP APIs | [api/README.md](api/README.md) |
| Kafka/event contracts | [events/README.md](events/README.md) |
| Ingestion adapters and connectors | [architecture/telemetry-ingestion.md](architecture/telemetry-ingestion.md) |
| Alert trust boundary | [architecture/alert-service-trust-boundary.md](architecture/alert-service-trust-boundary.md) |
| Simulator | [simulation/README.md](simulation/README.md) |

## Operations And Verification

| Topic | Document |
| --- | --- |
| Operations runbooks | [operations/README.md](operations/README.md) |
| Verification flows | [flows/README.md](flows/README.md) |
| Integration notes | [integrations/README.md](integrations/README.md) |

Target direction lives in [../target/README.md](../target/README.md).
Historical rationale lives in [../adr/README.md](../adr/README.md). It is not
the source of truth for current runtime behavior.
