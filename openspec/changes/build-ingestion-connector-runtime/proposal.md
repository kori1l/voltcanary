# Change Proposal: Build Ingestion Connector Runtime

## Why

Active connectors such as HTTP polling, Modbus polling, OPC UA subscriptions,
and vendor API polling cannot be scaled like stateless HTTP ingress. The service
needs a connector runtime that owns assignment, leases, lifecycle, scheduling,
health, and retries independently from concrete adapters.

This change implements phase 6 from
`docs/voltcanary_target_ingestion_architecture.md`.

## What Changes

- Add connector runtime, scheduler, supervisor, assignment manager, lease
  manager, and health model.
- Ensure only one runtime owner actively runs a source connector at a time.
- Make adapters implement polling/subscription behavior without owning global
  source ownership.
- Add retry/backoff and lease-loss behavior before adding industrial protocols.

## Non-Goals

- Do not add HTTP polling, Modbus, or OPC UA adapters in this change.
- Do not make each adapter create its own scheduler or ownership mechanism.
- Do not introduce a separate deployable connector service yet.

## Impacted Areas

- `backend/services/ingestion-service`
- `deploy/` if lease store or runtime profile configuration is introduced
- `docs/voltcanary_target_ingestion_architecture.md`
- `openspec/specs/ingestion-scaling-runtime/spec.md`
