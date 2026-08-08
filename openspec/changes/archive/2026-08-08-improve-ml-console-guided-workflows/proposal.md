## Why

The ML Settings console now proves the failure-prediction lifecycle can work
end-to-end, but the operator workflow is still too manual. During the local
workflow an operator had to copy raw device ids, model ids, metric names, and a
feature snapshot id between tabs. Some required failure-prediction fields are
missing from the feature snapshot UI, approval gates can be blocked by frontend
metric parsing, and partially filled forms are lost when switching tabs.

This makes the ML lifecycle hard to use correctly and hides whether the operator
is building an anomaly workflow or a supervised failure-prediction workflow.

## What Changes

- Add guided ML workflows that separate anomaly detection from failure
  prediction across dataset, label, feature snapshot, training, model, policy,
  settings, and monitoring steps.
- Replace raw UUID/text entry with searchable selectors for devices, metrics,
  datasets, feature snapshots, models, and deployment scopes.
- Persist form drafts per account and workflow while the operator switches tabs.
- Expose model-family-specific feature snapshot fields in the UI, including
  `FAILURE_PREDICTION` and `horizonDays`.
- Fix model registry cards to parse and display backend metrics consistently so
  approval gates match backend approval behavior.
- Add stronger client-side validation, correct field types, readable field help,
  and responsive layouts for long identifiers and dense forms.

## Capabilities

### New Capabilities

- `ml-console-workflows`: guided operator UX for ML lifecycle workflows.

### Changed Capabilities

- `ml-feature-platform`: feature snapshot UI must support model family and
  horizon selection.
- `ml-failure-outcomes`: label workflows must be separated by generated
  horizon labels versus manual correction.
- `ml-failure-prediction`: frontend approval and training gates must use the
  same metric contract as backend.

## Impact

- `frontend/src/pages/MlSettingsPage.tsx`: workflow shell, selectors, forms,
  drafts, validation, model cards, policy editor, monitoring context.
- `frontend/src/analytics/**`: API types, metric parsing, selector data
  loading, optional draft persistence helpers.
- `backend/services/analytics-service/**`: API shape may need typed metric
  fields in addition to JSON strings where frontend currently receives raw JSON.
- `backend/services/device-service/**` and `telemetry-service/**`: read APIs may
  be used to populate device and metric pickers.
- `docs/operations/**`: update operator workflow documentation.

## Dependencies

Depends on the existing archived ML dataset lifecycle, feature platform,
artifact runtime, failure outcome labeling, supervised failure prediction, and
deployment runtime monitoring work. This change does not introduce a new model
algorithm.
