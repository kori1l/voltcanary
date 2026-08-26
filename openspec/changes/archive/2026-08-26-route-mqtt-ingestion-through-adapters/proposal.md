# Change Proposal: Route MQTT Ingestion Through Adapters

## Why

MQTT currently reaches the common pipeline through subscriber-specific mapping.
After HTTP proves the adapter path, MQTT should be split into transport
connection handling, topic resolution, payload mapping, and a canonical MQTT
adapter.

This change implements phase 3 from
`docs/voltcanary_target_ingestion_architecture.md`.

## What Changes

- Split the MQTT subscriber into MQTT ingress/transport and MQTT telemetry
  adapter responsibilities.
- Keep topic parsing and payload mapping protocol-specific and outside the
  common pipeline.
- Register MQTT adapter metadata and capabilities in the adapter registry.
- Ensure HTTP and MQTT converge at the same canonical adapter boundary.

## Non-Goals

- Do not add MQTT command support or vendor-specific MQTT adapters yet.
- Do not keep the old subscriber-to-pipeline shortcut after the adapter route is
  in place.
- Do not add active connector ownership in this change.

## Impacted Areas

- `backend/services/ingestion-service/src/main/java/com/voltcanary/ingestion/mqtt`
- `backend/services/ingestion-service/src/main/java/com/voltcanary/ingestion/pipeline`
- `docs/api/ingestion-api.md`
- `openspec/specs/ingestion-architecture/spec.md`
