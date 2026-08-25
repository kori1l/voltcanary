# Change Proposal: Add Modbus TCP Ingestion Adapter

## Why

Modbus TCP is the first industrial adapter and the main test that the new
architecture actually isolates protocol details. Adding Modbus must not require
changes to the common ingestion pipeline.

This change implements phase 8 from
`docs/voltcanary_target_ingestion_architecture.md`.

## What Changes

- Add a generic Modbus TCP polling adapter with versioned register mapping
  configuration.
- Use connector runtime ownership, scheduling, health, retry, and credentials.
- Convert Modbus registers and units into canonical telemetry metrics.
- Add tests proving common pipeline code remains protocol-agnostic.

## Non-Goals

- Do not add OPC UA subscription behavior in this change.
- Do not add Modbus command/write support.
- Do not let Modbus register concepts leak into the canonical pipeline model.

## Impacted Areas

- `backend/services/ingestion-service`
- dependency declarations for the selected Modbus library
- docs/events or operations docs for adapter config examples
- `openspec/specs/ingestion-protocol-adapters/spec.md`
