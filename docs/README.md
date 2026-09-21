# VoltCanary Documentation

This directory is organized by document role. Start here when looking for a
topic, then follow the folder-level index for the specific contract or runbook.

## Where To Go

| Need | Start here | Notes |
| --- | --- | --- |
| Current system shape | [current](current/README.md) | Entry point for behavior that exists in code today |
| Target architecture | [target](target/README.md) | Entry point for where the system is going |
| HTTP contracts | [current/api](current/api/README.md) | Public gateway routes and service API contracts implemented in code |
| Kafka/event contracts | [current/events](current/events/README.md) | Event envelope, topic keys, producer/consumer ownership implemented in code |
| Operations and local deployment | [current/operations](current/operations/README.md) | Local Kubernetes, connector operation, ML operation, repository structure |
| Verification | [current/flows](current/flows/README.md) | Canonical E2E runbook, ML verification, maintenance checks |
| Durable decisions | [adr](adr/README.md) | Historical architecture decisions |
| Integration notes | [current/integrations](current/integrations/README.md) | Protocol or external integration notes |
| Simulator docs | [current/simulation](current/simulation/README.md) | Simulator service, frontend flow, YAML schema |
| Interview/demo context | [target/talking-points.md](target/talking-points.md) | Non-contract explanatory talking points |
| Load-test preflight | [current/operations/load-testing.md](current/operations/load-testing.md) | Current executable profile validation, arithmetic and sanitized evidence |
| Load testing and capacity evidence | [target/load-testing.md](target/load-testing.md) | Planned generators, execution procedure, evidence gates and tooling gaps |

## Current Vs Target

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
