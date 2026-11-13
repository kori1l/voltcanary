# VoltCanary Documentation

This directory is organized by document role. Start here when looking for a
topic, then follow the folder-level index for the specific contract or runbook.

## Where To Go

| Need | Start here | Notes |
| --- | --- | --- |
| Spring AI assistant runtime | [Current architecture](current/architecture/spring-ai-assistant.md) | Spring AI 2, Boot 4, durable context/tools and verification |
| VCL implementation baseline | [Parser/registry/static compiler](current/architecture/vcl-compiler-front-end.md) | First G0/M0 Java library; service/runtime activation not implemented |
| Native configurable data | [Reviewed owner API and workbench](current/api/configurable-native-data.md) | Native schemas, row CAS/history, bounded LIVE queries and manual /data console; snapshot/import and AI pending |
| AI Spring implementation design | [Target Spring bindings](target/ai-assistant-spring-implementation.md), [ADR-014](adr/ADR-014-ai-spring-extension-boundaries.md) | Beans, Advisors, AOP, transactions, workers, framework reuse and SPR01–SPR15; not implemented |
| Current system shape | [current](current/README.md) | Entry point for behavior that exists in code today |
| Target architecture | [target](target/README.md) | Entry point for where the system is going |
| Configurable data, customer DBs, map features, temporal rules and AI | [Complete target](target/configurable-operations-platform.md), [VCL](target/configurable-operations-language.md), [storage/adapters](target/configurable-operations-storage.md), [strict imports](target/configurable-operations-imports.md), [program](../openspec/changes/README.md#configurable-operations-program) | G0 kernels/bundles and native schema/row/LIVE owner increments implemented; full program remains pending |
| HSL example and event quality cases | [Retained scenario](target/transit-stop-monitoring-acceptance.md), [bus readiness/gap audit](target/configurable-operations-bus-readiness.md) | Specialized T1–T7 queue superseded by generic capabilities |
| Device variables and telemetry transformations | [current architecture](current/architecture/device-variables.md) | Opt-in implementation, contracts, operation and remaining release gates; [target](target/device-variables.md) |
| UI/UX audit and remaining fixes | [target/ui-design-requirements.md](target/ui-design-requirements.md) | Current review, completed redesign baseline, priorities, and acceptance criteria |
| User-created dashboards | [target/custom-dashboards.md](target/custom-dashboards.md) | Future business/persona scenarios, editor and widgets, permissions, calculation semantics, and acceptance |
| Dashboard optimization evidence | [research report](current/flows/dashboard-optimization-research.md) | Measured read/render bottlenecks, limits, loading strategy and prioritized future implementation |
| AI dashboard conversation reliability | [Target architecture](target/ai-dashboard-assistant.md) and [six-change program](../openspec/changes/README.md#ai-dashboard-reliability-program) | Proposed architecture and measured rollout; not current implementation |
| Assistant intents, skills, model routing and economical execution | [Target architecture and JSON formats](target/ai-assistant-orchestration.md), [migration program](../openspec/changes/README.md#ai-assistant-orchestration-program) | Proposed replacement for semantic regex routing and oversized prompts; implementation/live trials remain stopped |
| UI/UX and dashboard implementation proposals | [OpenSpec program](../openspec/changes/README.md) | Proposed changes, delivery order, dependencies, and audit/product requirement coverage |
| HTTP contracts | [current/api](current/api/README.md) | Public gateway routes and service API contracts implemented in code |
| Kafka/event contracts | [current/events](current/events/README.md) | Event envelope, topic keys, producer/consumer ownership implemented in code |
| Operations and local deployment | [current/operations](current/operations/README.md) | Local Kubernetes, connector operation, ML operation, repository structure |
| Verification | [current/flows](current/flows/README.md) | Canonical E2E runbook, ML verification, maintenance checks |
| Durable decisions | [adr](adr/README.md) | Historical architecture decisions |
| Integration notes | [current/integrations](current/integrations/README.md) | Protocol or external integration notes |
| Real Helsinki vehicle GPS | [HSL connector](current/operations/hsl-live-vehicles.md) | Public MQTT, real devices, common map and verification |
| Simulator docs | [current/simulation](current/simulation/README.md) | Simulator service, frontend flow, YAML schema |
| Interview/demo context | [target/talking-points.md](target/talking-points.md) | Non-contract explanatory talking points |
| Load-test preflight | [current/operations/load-testing.md](current/operations/load-testing.md) | Current executable profile validation, arithmetic and sanitized evidence |
| Load testing and capacity evidence | [target/load-testing.md](target/load-testing.md) | Planned generators, execution procedure, evidence gates and tooling gaps |

## Current Vs Target

AI-first priority (2026-11-11): [Java/LangGraph4j assessment and delivery order](../openspec/changes/README.md#ai-first-execution-priority),
[target framework amendment](target/ai-assistant-spring-implementation.md#langgraph4j-assessment-and-migration-2026-11-11).
LangGraph4j core is integrated for new turns/context/confirmation with atomic
plan checkpoints; [current boundaries](current/architecture/spring-ai-assistant.md#langgraph4j-core-integration--2026-11-11)
and [ADR-015](adr/ADR-015-library-backed-agent-graph.md) distinguish implemented
core from pending specialists, in-place migration and model-quality acceptance.

AI reviewed creation: [API](current/api/ai-agent-api.md),
[task recovery](current/operations/ai-reviewed-tasks.md),
[verification](current/flows/ai-creation-verification.md).

Documentation uses these labels consistently:

| Label | Meaning | Where to look |
| --- | --- | --- |
| **Current implementation** | Behavior that exists in the codebase now. It should be backed by controllers, event classes, migrations, frontend clients, deployment manifests, or tests. | `docs/current/` |
| **Target architecture** | Direction we want but do not fully run as production behavior yet. Target-only behavior must not be written as current behavior. | `docs/target/` |
| **Historical decision** | Why a decision was made. ADRs can mention current alignment, but they are not the source of truth for current runtime behavior. | `docs/adr/*` |

If a topic has both current and target content, keep the current contract under
`docs/current/` and move the target-only material into a matching document under
`docs/target/`.

Current docs align to the codebase, not to speculative specs or archived
planning notes.

## Source Of Truth Order

When documents overlap, prefer this order:

1. Code: controllers, DTOs, event classes, migrations, frontend clients,
   deployment manifests, scripts, and tests.
2. `docs/current/api/*` and `docs/current/events/*` for current public and
   asynchronous contracts.
3. `docs/current/*` for implemented architecture and runtime contracts.
4. `docs/target/*` for target direction and explicitly marked gaps.
5. `docs/current/operations/*` and `docs/current/flows/*` for how to run or
   verify current behavior.
6. ADRs for historical decisions; do not rewrite them to describe new state.

OpenSpec files are not the source of truth for this documentation split unless
a later task explicitly asks to reconcile specs.

## Documentation Rules

- Prefer updating the existing authoritative document for a topic.
- Add new docs under the correct folder and link them from that folder README.
- Do not keep large one-off implementation plans in `docs/`; convert durable
  behavior into API/event contracts, architecture docs, or operations runbooks.
- If a doc says a feature is planned but code shows it is implemented,
  update the doc or delete the stale plan.

Vehicle GPS: [setup and map verification](current/flows/vehicle-gps-verification.md).
Vehicle monitoring: [fleet/history/zones verification](current/flows/vehicle-monitoring-verification.md).

AI architecture implementer details: [requirements, nested consultations, metric evidence and RAG](target/ai-assistant-collaboration-and-retrieval.md).
Configurable owner API details: [reservations, reviews and dependent steps](target/configurable-operations-control.md).
