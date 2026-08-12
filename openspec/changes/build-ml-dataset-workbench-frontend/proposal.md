# Build ML Dataset Workbench Frontend

## Why

The current ML Settings dataset UI is not friendly for a data engineer. It asks
the user to fill a backend-style form without first showing real metric
availability, telemetry density, candidate windows, gaps, or failure-label
readiness.

The platform change `build-ml-dataset-workbench` defines the backend preview and
create contracts. This companion change defines the frontend delivery that makes
that contract usable in the ML console.

## What Changes

- Replace the internal telemetry dataset form with a Dataset Workbench.
- Add a searchable device scope selector for single devices and cohorts.
- Show a metric coverage matrix based only on preview-provided real metrics.
- Add a telemetry timeline with candidate windows, selected range, gap markers,
  maintenance exclusions, and failure/corrective-maintenance overlays.
- Add candidate window selection and brush-based time selection with exact
  datetime controls.
- Add a readiness panel with field-level block and warning reasons.
- Add explicit stale/loading/error preview states that control whether dataset
  creation is enabled.
- Carry workbench model family and horizon defaults into feature snapshot and
  training flows.
- Add frontend tests for state, payloads, empty states, and interactions.

## Impact

Affected specs:

- `ml-console-workflows`
- `ml-feature-platform`

Affected implementation areas:

- `frontend/src/analytics/mlModuleApi.ts`
- `frontend/src/pages/MlSettingsPage.tsx`
- `frontend/src/features/ml-datasets/`
- `frontend/src/app/styles.css`
- `frontend/src/pages/MlSettingsPage.test.tsx` or colocated workbench tests
- `frontend/src/test/setup.ts` if interaction helpers are needed

Dependency:

- Consumes preview and create contracts from
  `build-ml-dataset-workbench`.
- May be implemented behind mocked preview data during early UI work, but it
  cannot be considered complete until it uses the real analytics-service
  preview endpoint.

Compatibility:

- Existing external dataset import, feature snapshot, training, model registry,
  policy, and monitoring tabs remain available.
- Existing dataset create API behavior remains supported through the backend
  change; the frontend should use preview fields when available.
