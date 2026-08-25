# Change Proposal: Add OPC UA Ingestion Adapter

## Why

OPC UA adds long-lived subscription behavior after polling connectors are in
place. It validates connector runtime lifecycle, session ownership, retry,
credential handling, and canonical telemetry mapping for subscription-based
industrial protocols.

This change implements phase 9 from
`docs/voltcanary_target_ingestion_architecture.md`.

## What Changes

- Add a generic OPC UA subscription adapter with versioned node mapping
  configuration.
- Use connector runtime ownership for long-lived sessions and subscriptions.
- Convert OPC UA node changes into canonical telemetry input.
- Add lifecycle tests for session loss, reconnect, lease loss, and config
  updates.

## Non-Goals

- Do not add OPC UA command/write support.
- Do not add a separate industrial gateway service yet.
- Do not leak OPC UA NodeId concepts into the common ingestion pipeline.

## Impacted Areas

- `backend/services/ingestion-service`
- dependency declarations for the selected OPC UA client library
- operations docs for adapter config and certificate/credential references
- `openspec/specs/ingestion-protocol-adapters/spec.md`
