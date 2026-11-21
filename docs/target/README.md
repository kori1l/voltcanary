# Target Architecture

This folder is the entry point for where VoltCanary is going. Content here may
include behavior that is partially implemented, provisioned but unused, or not
implemented yet. Target-only behavior must not be described as current runtime
behavior.

## Start Here

| Topic | Document |
| --- | --- |
| Proposed Netty multiprotocol ingestion transport | [Layers, protocol migration, admission/ACK, rollout and ET01–ET12](ingestion-event-transport.md); documentation only, no capacity qualification |
| AI-first Java agent and LangGraph4j integration | [Execution priority](../../openspec/changes/README.md#ai-first-execution-priority), [current runtime and registered consultations](../current/architecture/spring-ai-assistant.md#langgraph4j-core-integration--2026-11-11), [graph decision and evidence](../adr/ADR-015-library-backed-agent-graph.md) |
| Platform target architecture, current baseline, and target gaps | [architecture.md](architecture.md) |
| AI Spring/Spring AI implementation architecture | [Components, Advisors, AOP, transaction/worker boundaries and SPR01–SPR15](ai-assistant-spring-implementation.md); [proposed ADR-014](../adr/ADR-014-ai-spring-extension-boundaries.md) |
| Bus scenario expressibility and readiness | [End-to-end gap audit and BUS01–BUS10](configurable-operations-bus-readiness.md): capture, occurrence binding, exact door predicate and non-arrival distinction |
| Configurable operations: complete target specification | [Architecture](configurable-operations-platform.md), [VCL types/methods](configurable-operations-language.md), [grammar](configurable-operations-language.schema.json), [examples](configurable-operations-language.examples.json), [storage/customer DB adapters](configurable-operations-storage.md), [strict imports](configurable-operations-imports.md), [A01–A66 acceptance](configurable-operations-acceptance.md); implementation stopped |
| HSL example: stop service and reviewed AI setup | [Historical specialized design](transit-stop-monitoring.md), [reusable event cases](transit-stop-monitoring-acceptance.md), [seed manifest](transit-stop-monitoring.scenarios.json); superseded implementation queue, retained scenario requirements |
| Proposed device variables: definitions, transformations, stored telemetry, charts and threshold alerts | [device-variables.md](device-variables.md) |
| Production readiness gaps | [production-readiness.md](production-readiness.md) |
| Target alert lifecycle Kafka topics | [alert-lifecycle-events.md](alert-lifecycle-events.md) |
| Notification delivery operations target | [notification-delivery-operations.md](notification-delivery-operations.md) |
| ML dataset workbench target design | [ml-dataset-workbench.md](ml-dataset-workbench.md) |
| Product roadmap context | [product-roadmap.md](product-roadmap.md) |
| Reliable conversational AI dashboard editing | [ai-dashboard-assistant.md](ai-dashboard-assistant.md): proposed state, operations, capability resolution, verification and bounded recovery |
| Assistant intents, server skills, dependent plans and economical provider-neutral models | [ai-assistant-orchestration.md](ai-assistant-orchestration.md): target architecture, HTTP/state contracts, context budgets, migration and acceptance |
| Proposed assistant-v2 JSON formats | [Canonical schemas](ai-assistant-protocol.schema.json) and [request/response examples](ai-assistant-protocol.examples.json); target-only, not implemented API |
| User-created dashboards: businesses, widgets, editor, permissions, and acceptance | [custom-dashboards.md](custom-dashboards.md) |
| Proposed UI/UX and dashboard changes: dependencies and requirement coverage | [OpenSpec program](../../openspec/changes/README.md) |
| UI audit, completed redesign baseline, and remaining UX fixes (7 October 2026) | [ui-design-requirements.md](ui-design-requirements.md) |
| Interview/demo talking points | [talking-points.md](talking-points.md) |
| Capacity claims, load-test procedure and tooling gaps | [load-testing.md](load-testing.md) |

## Target-Only Items Currently Called Out

Configurable-platform spec coverage:
[all active changes and alignment decisions](configurable-operations-spec-alignment.json).
Implementer protocol: [owner endpoints, reservations, reviews and recovery](configurable-operations-control.md).
Documentation grammar/semantic vectors: `npm run vcl:check` (PowerShell7 + Node);
these checks do not establish runtime acceptance.

| Item | Status |
| --- | --- |
| Production deployment with immutable images, external secrets, ingress/TLS, stateful HA, network policies, SLOs, backup/restore, and runbooks | [production-readiness.md](production-readiness.md) |
| Workspace/tenant administration, onboarding, metering, billing, support operations, public API keys, and enterprise controls | [product-roadmap.md](product-roadmap.md) and [production-readiness.md](production-readiness.md) |
| Private dashboard editor, full-scope aggregates, and later team publication with independent resource grants | [custom-dashboards.md](custom-dashboards.md) |
| Kafka alert lifecycle topics such as `alert.created.v1`, `alert.acknowledged.v1`, and `alert.resolved.v1` | [alert-lifecycle-events.md](alert-lifecycle-events.md) |
| Notification test delivery, channel diagnostics, and bounded retry operations | [notification-delivery-operations.md](notification-delivery-operations.md) |
| Production hardening for real connector credentials, certificates, network egress, protocol integration tests, dashboards, and runbooks | [production-readiness.md](production-readiness.md) |
| Production consumers/runbooks for `device-registry.changed.v1` and DLQ replay tooling | [production-readiness.md](production-readiness.md) |

For current behavior, start at [../current/README.md](../current/README.md).

## Requirements, nested consultations and knowledge retrieval amendment

The [collaboration and retrieval contract](ai-assistant-collaboration-and-retrieval.md)
extends the existing durable runtime with typed requirement slots, bounded nested
specialist tasks, exact metric evidence and optional PostgreSQL/pgvector knowledge
retrieval. It preserves existing review/receipt authority, PINNED defaults and
shared budgets. [Consultation grammar](ai-assistant-collaboration.schema.json),
[examples](ai-assistant-collaboration.examples.json); `npm run ai-contracts:check`
validates documentation only. Implementation and real-model trials remain stopped.

The collaboration contract specification revision2 additionally defines dependency-aware
rebase, dual question/requirements CAS, absolute budget deadlines, private ID-only
retrieval and requirement-to-effect/evidence quality gates AI01-AI24. Its JSON
examples and selected semantic vectors are checked by `npm run ai-contracts:check`.

## Execution status — resumed2026-11-03

Configurable implementation has resumed with G0/M0.
[Current common-vcl parser/registry/static compiler](../current/architecture/vcl-compiler-front-end.md)
is verified; scalar/runtime/owner/UI/AI integration remains pending. Older target
stop labels describe the planning phase; use the OpenSpec index execution status
and active G0 tasks for the next implementation. Live model campaigns are not resumed.
