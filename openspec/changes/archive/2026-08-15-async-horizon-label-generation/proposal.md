# Async Horizon Label Generation

## Why

Horizon label generation currently runs behind a synchronous
`POST /analytics/ml/datasets/horizon-labels` request. For larger datasets the
operation can exceed the API Gateway response timeout, causing the ML console to
show a transport error even though the operator initiated a valid long-running
workflow.

Operators need the generation step to behave like a durable workflow: start the
work, see a status, refresh or poll progress, and inspect the generated label
summary when it completes.

## What Changes

- Introduce an async horizon-label generation job lifecycle in analytics-service.
- Return a job identifier and status immediately when generation is accepted.
- Add a status endpoint that reports `QUEUED`, `RUNNING`, `COMPLETED`, or
  `FAILED`, plus progress metadata and final generation summary when available.
- Persist job state, request parameters, lineage/config hash, ownership, and
  sanitized failure details.
- Make repeated start requests idempotent for the same dataset version, horizon,
  generation version, preventive-maintenance flag, and input lineage hash.
- Update the ML console Labels tab to start generation, poll/check status, and
  stop rendering gateway timeout as a user-facing failure.
- Update API docs and tests for async status behavior.

## Impact

Affected specs:

- `ml-failure-outcomes`
- `ml-console-workflows`
- `ml-dataset-lifecycle`

Affected implementation areas:

- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/controller/MlDatasetController.java`
- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/service/MlHorizonLabelGenerationService.java`
- analytics-service persistence/Flyway migration for generation jobs
- analytics-service DTOs and tests for start/status endpoints
- `frontend/src/analytics/mlModuleApi.ts`
- `frontend/src/pages/MlSettingsPage.tsx`
- `frontend/src/pages/MlSettingsPage.test.tsx`
- `docs/api/` ML API contract documentation

Compatibility:

- Existing generated `ml_labels` lineage remains valid.
- Existing callers that expect synchronous completion must migrate to the async
  contract or use a temporary compatibility response only when a job completes
  inside the initial request budget.
