# Tasks

## 1. OpenSpec And Documentation

- [x] 1.1 Apply this change before implementation.
- [x] 1.2 Update `docs/api/ml-api.md` with the preview endpoint, optional create
  preview fields, response schemas, and error behavior.
- [x] 1.3 Keep `docs/architecture/11-ml-dataset-workbench.md` aligned with the
  implemented contract.
- [x] 1.4 Update local-ops skills only if the implementation changes repeatable
  workflow rules or validation commands.

## 2. Backend Preview Contract

- [x] 2.1 Add preview request/response DTOs in analytics-service.
- [x] 2.2 Add `POST /analytics/ml/datasets/internal-telemetry/preview`.
- [x] 2.3 Reuse or extract internal telemetry validation logic so preview and
  create evaluate the same quality gates.
- [x] 2.4 Return available metrics from selected devices without phantom default
  metrics.
- [x] 2.5 Implement common and all-available metric coverage modes.
- [x] 2.6 Implement candidate windows for best coverage, recent stable,
  failure-rich, and user-selected ranges.
- [x] 2.7 Return quality gates, blocking reasons, warning reasons, selected
  window, create defaults, preview id/hash, and expiry.
- [x] 2.8 Enforce account/device authorization and sanitize dependency errors.

## 3. Create Compatibility

- [x] 3.1 Add optional `previewId` and `previewHash` fields to internal telemetry
  dataset creation.
- [x] 3.2 Revalidate create requests and return a structured stale-preview
  conflict when supplied preview data no longer matches.
- [x] 3.3 Preserve legacy create behavior when no preview fields are supplied.
- [x] 3.4 Store selected preview summary in the final validation report
  `qualityProfile`.

## 4. Failure Prediction Readiness

- [x] 4.1 Preview failure horizon readiness without creating or replacing labels.
- [x] 4.2 Count positive, normal, unresolved, maintenance-excluded, and
  unobservable rows.
- [x] 4.3 Block or warn according to configured minimum positive labels and label
  coverage gates.
- [x] 4.4 Preserve explicit label generation as a separate lineage-changing
  action.

## 5. Tests And Verification

- [x] 5.1 Add analytics-service tests for preview validation, authorization,
  metric coverage, candidate scoring, label readiness, preview hash, stale
  create, and legacy create.
- [x] 5.2 Extend Docker smoke verification to cover preview, create, feature
  snapshot, training, deployment, live recalculation, and lifecycle cleanup.
- [x] 5.3 Run backend, Docker, and OpenSpec validation before archive.
