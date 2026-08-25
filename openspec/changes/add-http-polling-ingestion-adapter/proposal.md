# Change Proposal: Add HTTP Polling Ingestion Adapter

## Why

HTTP polling is the simplest active connector and should validate the connector
runtime before industrial protocols are added. It exercises scheduling,
ownership, credentials, retries, mapping, health, and canonical telemetry
handoff without Modbus or OPC UA complexity.

This change implements phase 7 from
`docs/voltcanary_target_ingestion_architecture.md`.

## What Changes

- Add a generic HTTP polling adapter with versioned adapter configuration.
- Use connector runtime scheduling, leasing, health, and retry behavior.
- Map polled HTTP responses into canonical telemetry input.
- Resolve HTTP polling credentials through credential references.

## Non-Goals

- Do not add Modbus or OPC UA behavior.
- Do not let the HTTP polling adapter own source assignment or global scheduling.
- Do not store raw vendor payloads in canonical telemetry events.

## Impacted Areas

- `backend/services/ingestion-service`
- `docs/api/ingestion-api.md` or operations docs for source config examples
- `openspec/specs/ingestion-protocol-adapters/spec.md`
