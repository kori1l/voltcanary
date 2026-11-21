# ADR-016: Netty multiprotocol ingestion transport

## Status

Proposed, 2026-11-19. Documentation only; not implemented or capacity-qualified.
Extends [ADR-009](ADR-009-unified-telemetry-ingestion-service.md), which retains
the unified ingestion bounded context and canonical adapter boundary.

## Context

Many devices and short events across HTTP, broker MQTT and active industrial
connectors require explicit connection, scheduling and overload ownership.
HTTP MVC replacement alone cannot establish those contracts for all protocols.
Current synchronous pipeline calls and publisher ACK waits cannot run on I/O loops.
Numeric capacity targets and exact SDK integration capabilities remain unqualified.

## Decision

Use Spring-managed Netty listener/client transport beneath protocol-specific
framing/session/security handlers, capability-specific domain adapters and the
existing protocol-neutral canonical ingestion core. Bound admission, offload,
memory and completion. Preserve publication ACK and source authority semantics.
Keep the external MQTT broker, Kafka event contracts and downstream services.
Qualify every current ingress mode and each SDK's Netty/group lifecycle APIs;
allow SDK-owned Netty groups, and explicitly mark isolated legacy modes incomplete.
Use explicit endpoint selection; shared-port negotiation requires its own contract.

## Consequences

Connection concurrency and protocol lifecycle become explicit, with more demanding
buffer ownership, ordering, security and shutdown tests. Netty is not a CPU parsing
speed guarantee. Common admission/fairness/completion is shared even when SDK
groups cannot be shared. Mature clients are preferred to handwritten session
stacks; replacement remains possible after qualification. Bounded queues reject
or pause work under overload rather than hide it. No durable spool or Kafka
redesign is implied. Default rollout waits for full protocol parity, frozen
workload/resource/SLO gates and tested rollback.

## References

- [Detailed target contract and ET01–ET12](../target/ingestion-event-transport.md)
- [Change, staged design and unchecked implementation tasks](../../openspec/changes/adopt-netty-multiprotocol-ingestion/proposal.md)
- [Implemented ingestion architecture](../current/architecture/telemetry-ingestion.md)
