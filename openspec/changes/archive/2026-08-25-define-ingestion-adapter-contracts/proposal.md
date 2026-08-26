# Change Proposal: Define Ingestion Adapter Contracts

## Why

`ingestion-service` currently has a useful common pipeline, but protocol-specific
entry points still know too much about the pipeline shape. Before adding more
protocols, the service needs a permanent adapter boundary that turns HTTP, MQTT,
future industrial protocols, imports, and vendor payloads into one canonical
ingress model.

This change implements phase 1 from
`docs/voltcanary_target_ingestion_architecture.md`.

## What Changes

- Introduce adapter contracts, manifests, capabilities, adapter type, protocol
  type, transport type, and source runtime configuration model.
- Make `IncomingTelemetryMessage` an explicit canonical ingress contract with
  protocol origin and context fields instead of protocol-specific fields.
- Add an adapter registry and resolver/dispatcher contract that can be used by
  HTTP, MQTT, active connectors, and future protocol modules.
- Add repeatable architecture checks proving the common ingestion core does not
  depend on concrete protocol adapters.

## Non-Goals

- Do not add a new external protocol in this change.
- Do not preserve old internal package boundaries or compatibility facades when
  replacing them is simpler.
- Do not move TimescaleDB persistence into `ingestion-service`.

## Impacted Areas

- `backend/services/ingestion-service`
- `backend/common/common-events` if canonical event fields need adjustment
- `openspec/specs/ingestion-architecture/spec.md`
- `docs/voltcanary_target_ingestion_architecture.md`
- `docs/adr/ADR-009-unified-telemetry-ingestion-service.md`
