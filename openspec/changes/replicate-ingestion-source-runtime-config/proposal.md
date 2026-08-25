# Change Proposal: Replicate Ingestion Source Runtime Config

## Why

The target architecture requires `ingestion-service` to avoid synchronous
`device-service` lookups in the telemetry hot path. Each data-plane instance
needs a local source runtime configuration read model fed by compacted events so
push ingress and future active connectors can scale horizontally.

This change implements phase 4 from
`docs/voltcanary_target_ingestion_architecture.md`.

## What Changes

- Define and publish `source.runtime-config.v1` events from the control-plane
  source of truth.
- Add an ingestion-side consumer and local repository/cache for source runtime
  configuration.
- Resolve source identity, adapter type, protocol, transport, status, account,
  device, region, mapping, and credential references from the local read model.
- Remove `device-service` from the normal telemetry hot path after the read
  model is populated.

## Non-Goals

- Do not keep synchronous device-service fallback as a production hot-path
  requirement.
- Do not store raw secrets in source runtime configuration.
- Do not implement Modbus, OPC UA, or polling runtime behavior in this change.

## Impacted Areas

- `backend/services/device-service`
- `backend/services/ingestion-service`
- `backend/common/common-events`
- `docs/events/*.md`
- `docs/api/ingestion-api.md`
- `openspec/specs/ingestion-source-runtime-config/spec.md`
