# Repair ML Workbench Pipeline UX

## Why

The latest ML workbench frontend change was archived with all tasks checked, but
the visible ML Settings experience still reads as a dense form rather than a
guided machine-learning pipeline. Operators can select devices and still see
`0 metrics`, empty candidate windows, and an idle readiness panel until they
find and click a manual preview button. The telemetry chart also renders only
coarse coverage buckets because the backend preview does not return event
markers, and the frontend does not make the dataset -> labels -> snapshot ->
training path visible.

## What Changes

- Rework ML Settings around a visible anomaly/failure workflow pipeline.
- Make Dataset Workbench discovery run automatically once a valid device scope
  and time range exist, while retaining explicit refresh/retry controls.
- Replace the native device multi-select with a readable searchable checklist
  that keeps cohort counts visible.
- Make the timeline readable before and after preview, with visible candidate
  overlays, gap bands, maintenance/failure markers, selected range, and an
  operator legend.
- Extend backend preview timeline buckets with maintenance and failure outcome
  events so the frontend can render meaningful markers.
- Surface downstream readiness for labels, feature snapshots, training, model
  approval, deployment policy, and monitoring in the first viewport.
- Add tests that cover automatic preview, accessible selection, event marker
  rendering, and workflow step status rather than only request payloads.

## Impact

Affected specs:

- `ml-console-workflows`
- `ml-dataset-lifecycle`
- `ml-feature-platform`

Affected implementation areas:

- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/service/MlDatasetService.java`
- `backend/services/analytics-service/src/test/java/com/voltcanary/analytics/MlDatasetServiceTest.java`
- `frontend/src/features/ml-datasets/`
- `frontend/src/pages/MlSettingsPage.tsx`
- `frontend/src/pages/MlSettingsPage.test.tsx`
- `frontend/src/app/styles.css`
- `docs/architecture/11-ml-dataset-workbench.md`
