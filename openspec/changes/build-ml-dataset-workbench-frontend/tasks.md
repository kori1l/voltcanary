# Tasks

## 1. OpenSpec And UX Contract

- [x] 1.1 Apply this frontend change after or alongside
  `build-ml-dataset-workbench`.
- [ ] 1.2 Keep `docs/architecture/11-ml-dataset-workbench.md` aligned with the
  final UI behavior.
- [x] 1.3 Update `docs/api/ml-api.md` examples only after the backend preview
  contract is implemented.
- [ ] 1.4 Update frontend skill guidance if the workbench introduces repeatable
  validation or interaction rules for future agents.

## 2. Frontend API And Types

- [x] 2.1 Add preview request/response types to `frontend/src/analytics/mlModuleApi.ts`.
- [x] 2.2 Add a preview API client function using the shared `apiRequest`.
- [ ] 2.3 Add workbench model types and pure helpers for coverage, state, range,
  and status formatting.
- [ ] 2.4 Ensure stale or out-of-order preview responses cannot overwrite newer
  workbench state.

## 3. Workbench Composition

- [ ] 3.1 Replace the internal telemetry dataset form with a Dataset Workbench in
  ML Settings.
- [ ] 3.2 Extract workbench components under `frontend/src/features/ml-datasets/`
  when doing so reduces `MlSettingsPage.tsx` complexity.
- [ ] 3.3 Preserve existing dataset list, dataset detail, import, feature
  snapshot, training, registry, policy, and monitoring flows.
- [ ] 3.4 Preserve unsaved dataset drafts and mark stale restored devices or
  metrics.

## 4. Scope And Metrics

- [ ] 4.1 Implement searchable device scope selection with type/status filters.
- [ ] 4.2 Show selected cohort summary including active/inactive and
  telemetry-present/missing counts.
- [ ] 4.3 Render metric coverage matrix from preview-provided metrics only.
- [ ] 4.4 Implement common and all-available metric modes.
- [ ] 4.5 Remove selectable phantom fallback metrics from the dataset workflow.
- [ ] 4.6 Show empty state when selected devices expose no telemetry metrics.

## 5. Timeline And Range Selection

- [ ] 5.1 Render telemetry timeline with focused metric series.
- [ ] 5.2 Render candidate window overlays with ready/warning/blocked semantics.
- [ ] 5.3 Render selected range, gap markers, maintenance exclusions, and
  failure/corrective-maintenance markers when available.
- [ ] 5.4 Implement candidate selection from chart and candidate list.
- [ ] 5.5 Implement brush selection or an equivalent accessible range selection.
- [ ] 5.6 Keep brush selection and exact datetime fields synchronized.

## 6. Preview State And Readiness

- [ ] 6.1 Implement preview states: idle, loading, ready, stale, and error.
- [ ] 6.2 Mark preview stale when scope, metrics, range, family, horizon, split,
  feature config, or gates change.
- [ ] 6.3 Render readiness panel with scope, data quality, metric coverage,
  failure labels, downstream compatibility, and final decision.
- [ ] 6.4 Disable create when preview is loading, stale, errored, or blocked.
- [ ] 6.5 Render field-level blocking and warning reasons.

## 7. Dataset Create And Downstream Defaults

- [ ] 7.1 Submit dataset create with selected devices, metrics, range, split,
  feature config, quality gates, and preview fields when available.
- [ ] 7.2 Clear draft and show created dataset status and validation summary.
- [ ] 7.3 Default feature snapshot model family from workbench state.
- [ ] 7.4 Default failure snapshot horizon from workbench state.
- [ ] 7.5 Keep training selector warnings and blocks for model family and horizon
  mismatch.

## 8. Frontend Tests And Verification

- [ ] 8.1 Add tests for device search, cohort selection, and stale draft handling.
- [ ] 8.2 Add tests proving metric matrix uses preview metrics only and does not
  expose phantom fallback metrics.
- [ ] 8.3 Add tests for common/all-available metric modes.
- [ ] 8.4 Add tests for candidate selection and range synchronization.
- [ ] 8.5 Add tests for preview stale/loading/error/blocked states.
- [ ] 8.6 Add tests for failure horizon readiness rendering.
- [ ] 8.7 Add tests for dataset create payload with preview fields.
- [ ] 8.8 Run `cd frontend; npm run typecheck; npm run test; npm run build`.
- [ ] 8.9 Verify the workbench in the browser or Docker frontend service after
  implementation.
