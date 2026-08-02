## Why

Predictive maintenance cannot learn from maintenance windows alone. The current
maintenance data tells analytics when to suppress or reduce confidence, but not
what actually happened: confirmed failure, preventive maintenance, inspection
normal, false positive, component replacement, or unresolved issue.

VoltCanary needs structured maintenance outcomes and horizon label generation
before supervised failure prediction can be implemented.

## What Changes

- Add structured maintenance outcome records to device-service.
- Publish or expose outcome changes to analytics-service.
- Extend ML labels to represent failure, corrective/preventive maintenance,
  false positives, normal inspections, sensor faults, and unresolved anomalies.
- Generate deterministic horizon labels from outcomes.
- Add label quality and coverage reporting.
- Add UI workflows for outcome capture and label review.

## Capabilities

### New Capabilities

- `ml-failure-outcomes`: maintenance outcomes, failure label taxonomy, horizon
  label generation, and feedback loops.

## Impact

- `backend/services/device-service/**`: maintenance outcome domain/API/events.
- `backend/common/common-events/**`: outcome event contract if event-driven sync
  is selected.
- `backend/services/analytics-service/**`: outcome projection, label
  generation, label coverage reports.
- `frontend/src/**`: outcome entry and label review workflows.
- `docs/events/**`, `docs/api/**`, and `docs/operations/**`: outcome and label
  contracts.

## Dependencies

Depends on `close-ml-dataset-lifecycle`. It can proceed before or alongside
`materialize-ml-feature-platform`, but supervised model training depends on both.
