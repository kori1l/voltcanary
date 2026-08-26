# Change Proposal: Align Ingestion Target Architecture

## Why

The archived ingestion architecture sequence implemented the core data-plane
shape: adapter contracts, HTTP/MQTT ingress, replicated source runtime config,
shared multi-replica correctness state, connector runtime ownership, HTTP
polling, Modbus TCP, OPC UA, and horizontal scale verification.

The remaining gaps are smaller but important before the architecture is treated
as the target baseline:

- HTTP push ingress still binds directly to the concrete HTTP adapter instead
  of resolving inbound adapters through the same registry/dispatcher model used
  by active connectors.
- `device-service` source management can publish HTTP polling runtime config,
  but it does not expose first-class Modbus TCP or OPC UA source protocols even
  though ingestion already has those adapters.
- connector leases are sufficient for telemetry polling/subscription, but they
  do not yet expose fencing information needed before future command-capable
  adapters can safely reuse the ownership model.

## What Changes

- Route inbound HTTP, MQTT, and future push ingress through a registry-backed
  inbound dispatcher instead of hard-coding concrete adapters in orchestration.
- Extend device-service source protocol/runtime-config mapping so control-plane
  source records can represent `GENERIC_MODBUS_TCP` and `GENERIC_OPC_UA`
  sources with protocol, transport, region, mapping, adapter config, and
  credential references.
- Preserve protocol-specific mapping inside adapters while adding tests that
  prove adding or registering another inbound adapter does not require changing
  the common ingestion pipeline.
- Add lease fencing metadata to connector ownership so future command-capable
  adapters cannot rely on owner strings alone.
- Update ingestion API/events/operations/architecture docs to make the final
  target baseline explicit.

## Non-Goals

- Do not implement device command execution.
- Do not split `ingestion-service` into multiple deployables.
- Do not introduce a new storage backend for source runtime config.
- Do not rewrite already working adapter internals unless needed to preserve
  the target boundary.

## Impacted Areas

- `backend/services/ingestion-service`
- `backend/services/device-service`
- `backend/common/common-events`
- `docs/api/ingestion-api.md`
- `docs/events/*.md`
- `docs/architecture/*.md`
- `docs/operations/ingestion-connectors.md`
- `openspec/specs/ingestion-architecture/spec.md`
- `openspec/specs/ingestion-source-runtime-config/spec.md`
- `openspec/specs/ingestion-scaling-runtime/spec.md`
