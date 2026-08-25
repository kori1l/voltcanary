# Change Proposal: Route HTTP Ingestion Through Adapters

## Why

HTTP is the safest first real use of the adapter architecture because existing
single, batch, and import endpoints already converge on the pipeline. Refactoring
HTTP first proves the new boundary without adding a new protocol.

This change implements phase 2 from
`docs/voltcanary_target_ingestion_architecture.md`.

## What Changes

- Route HTTP single, batch, and import requests through an ingress orchestrator
  and HTTP adapter.
- Move REST DTO mapping responsibility out of the pipeline and into the HTTP
  adapter layer.
- Make batch ingestion process each item through the same canonical invariants
  as single-message ingestion.
- Replace old internal HTTP orchestration paths instead of preserving duplicate
  legacy behavior.

## Non-Goals

- Do not add MQTT, Modbus, OPC UA, or polling behavior in this change.
- Do not maintain an alternate controller-to-pipeline path once HTTP uses the
  adapter boundary.
- Do not redesign persistence in telemetry-service.

## Impacted Areas

- `backend/services/ingestion-service/src/main/java/com/voltcanary/ingestion/controller`
- `backend/services/ingestion-service/src/main/java/com/voltcanary/ingestion/rest`
- `backend/services/ingestion-service/src/main/java/com/voltcanary/ingestion/pipeline`
- `docs/api/ingestion-api.md`
- `openspec/specs/ingestion-architecture/spec.md`
