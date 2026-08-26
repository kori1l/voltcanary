# VoltCanary Documentation

This directory is organized by document role. Start here when looking for a
topic, then follow the folder-level index for the specific contract or runbook.

## Where To Go

| Need | Start here | Notes |
| --- | --- | --- |
| Current system shape | [architecture](architecture/README.md) | Service ownership, data flow, storage, security, deployment, scaling, consistency |
| Target architecture | [architecture/12-target-architecture.md](architecture/12-target-architecture.md) | Direction and links to owning specs |
| HTTP contracts | [api](api/README.md) | Public gateway routes and service API contracts |
| Kafka/event contracts | [events](events/README.md) | Event envelope, topic keys, producer/consumer ownership |
| Operations and local deployment | [operations](operations/README.md) | Local Kubernetes, connector operation, ML operation, repository structure |
| Verification | [flows](flows/README.md) | Canonical E2E runbook, ML verification, maintenance checks |
| Durable decisions | [adr](adr/README.md) | Historical architecture decisions |
| Integration notes | [integrations](integrations/README.md) | Protocol or external integration notes |
| Simulator docs | [simulation](simulation/README.md) | Simulator service, frontend flow, YAML schema |
| Interview/demo context | [interview/talking-points.md](interview/talking-points.md) | Non-contract explanatory talking points |

## Source Of Truth Order

When documents overlap, prefer this order:

1. `openspec/specs/*` for active product and architecture requirements.
2. `docs/api/*` and `docs/events/*` for public and asynchronous contracts.
3. `docs/architecture/*` for implemented architecture and target direction.
4. `docs/operations/*` and `docs/flows/*` for how to run or verify behavior.
5. ADRs for historical decisions; do not rewrite them to describe new state.

Archived OpenSpec changes are history only. Do not use them as active source
unless restoring or auditing a specific archived change.

## Documentation Rules

- Prefer updating the existing authoritative document for a topic.
- Add new docs under the correct folder and link them from that folder README.
- Do not keep large one-off implementation plans in `docs/`; convert durable
  behavior into OpenSpec specs, API/event contracts, architecture docs, or
  operations runbooks.
- If a doc says a feature is planned but code/specs show it is implemented,
  update the doc or delete the stale plan.
