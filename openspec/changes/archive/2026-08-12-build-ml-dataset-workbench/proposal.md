# Build ML Dataset Workbench

## Why

The current internal telemetry dataset UI is a backend-shaped form. A data
engineer must manually choose devices, metrics, and time bounds without seeing
metric availability, telemetry gaps, label readiness, or whether downstream
feature generation and training will pass.

This causes trial-and-error dataset creation and makes the failure-prediction
pipeline fragile. The platform already has immutable dataset versions,
validation reports, labels, feature snapshots, training runs, and Docker smoke
coverage. The missing layer is a production-ready workbench that previews the
same backend-owned validation before committing a dataset version.

## What Changes

- Add an analytics-owned internal telemetry dataset preview contract.
- Recommend candidate time windows before dataset creation.
- Return real metric availability from selected devices instead of default
  fallback metrics.
- Add failure-prediction label readiness to preview without mutating labels.
- Support preview-bound dataset creation through optional preview identity.
- Extend tests and Docker smoke checks so preview, create, feature snapshot,
  training, deployment, and lifecycle cleanup remain covered.

## Impact

Affected specs:

- `ml-dataset-lifecycle`
- `ml-failure-prediction`

Frontend delivery is covered by companion change
`build-ml-dataset-workbench-frontend`.

Affected implementation areas:

- `backend/services/analytics-service`
- `docs/api/ml-api.md`
- `docs/architecture/11-ml-dataset-workbench.md`
- `scripts/ml-failure-prediction-docker-smoke.ps1`

Compatibility:

- Existing `POST /analytics/ml/datasets/internal-telemetry` callers remain
  supported.
- Dataset creation remains authoritative and must still validate even when no
  preview hash is supplied.
- Preview is read-only and must not create dataset versions, labels, feature
  snapshots, training runs, or model versions.
