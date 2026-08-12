# Tasks

## 1. OpenSpec And UX Contract

- [x] 1.1 Apply this frontend change after or alongside
  `build-ml-dataset-workbench`.
- [x] 1.2 Keep `docs/architecture/11-ml-dataset-workbench.md` aligned with the
  final UI behavior.
- [x] 1.3 Update `docs/api/ml-api.md` examples only after the backend preview
  contract is implemented.
- [x] 1.4 Update frontend skill guidance if the workbench introduces repeatable
  validation or interaction rules for future agents.

## 2. Frontend API And Types

- [x] 2.1 Add preview request/response types to `frontend/src/analytics/mlModuleApi.ts`.
- [x] 2.2 Add a preview API client function using the shared `apiRequest`.
- [x] 2.3 Add workbench model types and pure helpers for coverage, state, range,
  and status formatting.
- [x] 2.4 Ensure stale or out-of-order preview responses cannot overwrite newer
  workbench state.

## 3. Workbench Composition

- [x] 3.1 Replace the internal telemetry dataset form with a Dataset Workbench in
  ML Settings.
- [x] 3.2 Extract workbench components under `frontend/src/features/ml-datasets/`
  when doing so reduces `MlSettingsPage.tsx` complexity.
- [x] 3.3 Preserve existing dataset list, dataset detail, import, feature
  snapshot, training, registry, policy, and monitoring flows.
- [x] 3.4 Preserve unsaved dataset drafts and mark stale restored devices or
  metrics.

## 4. Scope And Metrics

- [x] 4.1 Implement searchable device scope selection with type/status filters.
- [x] 4.2 Show selected cohort summary including active/inactive and
  telemetry-present/missing counts.
- [x] 4.3 Render metric coverage matrix from preview-provided metrics only.
- [x] 4.4 Implement common and all-available metric modes.
- [x] 4.5 Remove selectable phantom fallback metrics from the dataset workflow.
- [x] 4.6 Show empty state when selected devices expose no telemetry metrics.

## 5. Timeline And Range Selection

- [x] 5.1 Render telemetry timeline with focused metric series.
- [x] 5.2 Render candidate window overlays with ready/warning/blocked semantics.
- [x] 5.3 Render selected range, gap markers, maintenance exclusions, and
  failure/corrective-maintenance markers when available.
- [x] 5.4 Implement candidate selection from chart and candidate list.
- [x] 5.5 Implement brush selection or an equivalent accessible range selection.
- [x] 5.6 Keep brush selection and exact datetime fields synchronized.

## 6. Preview State And Readiness

- [x] 6.1 Implement preview states: idle, loading, ready, stale, and error.
- [x] 6.2 Mark preview stale when scope, metrics, range, family, horizon, split,
  feature config, or gates change.
- [x] 6.3 Render readiness panel with scope, data quality, metric coverage,
  failure labels, downstream compatibility, and final decision.
- [x] 6.4 Disable create when preview is loading, stale, errored, or blocked.
- [x] 6.5 Render field-level blocking and warning reasons.

## 7. Dataset Create And Downstream Defaults

- [x] 7.1 Submit dataset create with selected devices, metrics, range, split,
  feature config, quality gates, and preview fields when available.
- [x] 7.2 Clear draft and show created dataset status and validation summary.
- [x] 7.3 Default feature snapshot model family from workbench state.
- [x] 7.4 Default failure snapshot horizon from workbench state.
- [x] 7.5 Keep training selector warnings and blocks for model family and horizon
  mismatch.

## 8. Frontend Tests And Verification

- [x] 8.1 Add tests for device search, cohort selection, and stale draft handling.
- [x] 8.2 Add tests proving metric matrix uses preview metrics only and does not
  expose phantom fallback metrics.
- [x] 8.3 Add tests for common/all-available metric modes.
- [x] 8.4 Add tests for candidate selection and range synchronization.
- [x] 8.5 Add tests for preview stale/loading/error/blocked states.
- [x] 8.6 Add tests for failure horizon readiness rendering.
- [x] 8.7 Add tests for dataset create payload with preview fields.
- [x] 8.8 Run `cd frontend; npm run typecheck; npm run test; npm run build`.
- [x] 8.9 Verify the workbench in the browser or Docker frontend service after
  implementation.
