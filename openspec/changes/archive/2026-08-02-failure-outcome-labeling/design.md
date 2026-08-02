## Context

Failure prediction requires labels tied to future outcomes. Current labels
combine anomaly/degradation/maintenance concepts, but supervised failure risk
needs a clear positive event definition.

Maintenance windows are operational exclusion intervals. They should not be
treated as failure labels unless a structured outcome says a failure or
corrective maintenance occurred.

## Goals

- Capture maintenance and inspection outcomes as structured data.
- Convert outcomes into horizon-specific training labels.
- Keep label generation deterministic and auditable.
- Support false-positive feedback and normal inspection feedback.

## Non-Goals

- Do not train a supervised failure model in this change.
- Do not redesign all maintenance scheduling behavior.
- Do not infer failure labels from free-text notes alone.

## Design

### Outcome Model

Add a maintenance outcome concept with fields such as:

- `outcomeId`
- `accountId`
- `deviceId`
- `operationId` or `maintenanceWindowId`
- `observedAt`
- `resolvedAt`
- `outcomeType`
- `failureMode`
- `component`
- `severity`
- `downtimeMinutes`
- `technicianNotes`
- `source`
- `confidence`
- creator/editor audit metadata.

Initial outcome types:

- `CONFIRMED_FAILURE`
- `CORRECTIVE_MAINTENANCE`
- `PREVENTIVE_MAINTENANCE`
- `INSPECTION_NORMAL`
- `FALSE_POSITIVE`
- `SENSOR_FAULT`
- `UNRESOLVED_ANOMALY`
- `UNKNOWN`

### Label Generation

For a selected horizon, labels should be generated from outcomes:

- positive rows are rows whose `predictionAt` falls before a confirmed failure
  or corrective maintenance event within the horizon;
- normal rows are rows with no positive outcome within the horizon and enough
  future observation window;
- rows during maintenance exclusions are dropped or flagged according to feature
  config;
- unresolved anomalies are not positive failure labels by default.

Generated labels must store generation version, source outcome ids, horizon,
and lineage.

### Label Quality

Dataset detail should show:

- label coverage;
- positive count by horizon;
- outcome count by type;
- unknown/unresolved count;
- false-positive feedback count;
- minimum readiness gates for supervised training.

## Risks

- Operators may not fill outcome fields consistently. The UI should make common
  outcome choices easy and keep free text secondary.
- Outcomes can arrive after predictions. Label generation must account for label
  delay and avoid marking recent rows normal before the horizon is observable.
- Device-type-specific failure modes should be controlled values, but the first
  implementation can support generic values plus free-text notes.
