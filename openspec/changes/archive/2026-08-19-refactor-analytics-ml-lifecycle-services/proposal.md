# Change Proposal: Refactor Analytics ML Lifecycle Services

## Why

The analytics-service ML lifecycle implementation has several service classes
that are too large to review safely and mix independent responsibilities:

- `MlDatasetService.java` is roughly 1800 lines and owns preview, telemetry
  validation, candidate windows, timeline events, dataset creation,
  materialization, lifecycle cleanup, hashing, JSON mapping, and DTO mapping.
- `AnalyticsRecalculationService.java` is roughly 1000 lines and combines job
  orchestration, feature/baseline construction, ML calls, fallback behavior,
  persistence, risk/recommendation derivation, hashing, and response mapping.
- `MlFeaturePipelineService.java` is roughly 700 lines and combines dataset
  loading, imported/internal feature generation, label lookups, row persistence,
  lifecycle cleanup, hashing, and response mapping.
- `MlModelRegistryService.java` is roughly 700 lines and combines model list,
  approval/rejection/archive/delete, deployment policy upsert/rollback/resolve,
  demo/system model handling, artifact metadata validation, audit, and DTO
  mapping.

This makes ML behavior harder to verify and increases the chance that future
configuration or lifecycle additions will be appended to already overloaded
services.

## What Changes

- Split analytics ML lifecycle code into focused application/use-case services,
  domain helpers, mappers, and adapter-facing clients.
- Keep controllers and public API contracts compatible while moving work behind
  narrower collaborators.
- Preserve existing ML dataset, feature snapshot, training, model registry,
  policy resolution, analytics recalculation, fallback, and audit semantics.
- Add targeted tests around each extracted use case and a small integration
  check proving the facades preserve behavior.

## Non-Goals

- Do not change ML lifecycle API shapes or route paths.
- Do not introduce a new framework or module boundary.
- Do not rewrite persistence schema unless a targeted extraction uncovers a
  required compatibility fix.
- Do not broaden the frontend workflow in this change.

## Impacted Areas

- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/service/MlDatasetService.java`
- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/service/AnalyticsRecalculationService.java`
- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/service/MlFeaturePipelineService.java`
- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/service/MlModelRegistryService.java`
- Supporting analytics `ml`, `dto`, `repository`, and test packages
- `openspec/specs/backend-architecture/spec.md`
- `openspec/specs/ml-dataset-lifecycle/spec.md`
- `openspec/specs/ml-feature-platform/spec.md`
- `openspec/specs/ml-artifact-runtime/spec.md`
- `openspec/specs/ml-deployment-operations/spec.md`

## Open Questions

- Should the first implementation keep old facade class names to minimize
  controller/test churn, or move controllers directly to new use-case services?
- Should analytics recalculation be split in this change or sequenced after the
  dataset/feature/registry extractions to reduce merge risk?
