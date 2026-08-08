## Context

The current ML Settings console exposes the backend DTO shape directly. It has
the right backend pieces, but the operator must know internal ids and the hidden
ordering of operations. The same page mixes:

- anomaly detection;
- supervised failure prediction;
- generated labels;
- manual label correction;
- candidate model approval;
- policy activation;
- latest prediction inspection.

That made the verified failure-prediction path depend on manual API calls for
feature snapshot generation and model approval.

## Goals

- Make the correct workflow discoverable from the UI.
- Prevent copy/paste mistakes between model ids and device ids.
- Allow operators to choose existing devices, metrics, datasets, snapshots, and
  models by name and status while still exposing copyable ids.
- Keep drafts when switching tabs or moving between workflow steps.
- Make anomaly and failure-prediction workflows distinct enough that the wrong
  feature snapshot or horizon cannot be selected accidentally.
- Make frontend gates display the same model metrics that backend approval uses.
- Preserve dense, work-focused UI without card nesting or oversized marketing
  layout.

## Non-Goals

- Do not add a new training algorithm.
- Do not change the definition of labels, features, or deployment policy
  resolution.
- Do not hide advanced ids from engineers; make ids copyable secondary metadata.
- Do not rely on local demo seed data for production behavior.

## Design

### Workflow Shell

ML Settings should expose explicit workflow modes:

1. Anomaly detection.
2. Failure prediction.

The selected mode should scope labels, feature snapshot defaults, training
inputs, model lists, policy candidates, and monitoring expectations. Operators
can still switch tabs, but each tab should show where it sits in the chosen
workflow and which previous step is missing.

### Selectors

Raw UUID textareas should be replaced by selectors backed by existing APIs:

- device multi-select with name, type, status, location, and id;
- metric multi-select from selected devices' telemetry metrics;
- dataset selector with name, version, status, row count, date range, and id;
- feature snapshot selector with model family, horizon, row count, schema hash,
  created time, and id;
- model selector with family, algorithm, status, origin, version, horizon, and
  id;
- policy scope selector that reveals the correct device or device-type picker.

Advanced users should be able to paste an id into searchable pickers, but not
be forced to find ids elsewhere.

### Draft Persistence

Drafts should be stored per account, route, tab, workflow mode, and form. Local
browser storage is sufficient for unsaved form values, but saved server state
must remain the source of truth. Drafts should be cleared after successful
submission or by an explicit reset action.

### Failure Feature Snapshot

Feature snapshot generation must expose:

- model family;
- horizon days when family is `FAILURE_PREDICTION`;
- feature template and version;
- feature families;
- rolling window and missing gap settings.

The UI should validate that the feature snapshot horizon matches generated
labels and the training horizon.

### Labels Layout

Generated horizon labels and manual labels are separate jobs:

- generated labels derive from maintenance outcomes and dataset rows;
- manual labels correct or add review annotations.

The Labels tab should split those actions into separate panels or sub-tabs and
show label coverage, positives, normals, unobservable rows, and lineage before
the operator proceeds to training.

### Model Cards And Approval Gates

The frontend should consume a typed metrics object or reliably parse
`evaluationMetricsJson`. The model card must not show `N/A` for metrics that
backend returned. Approval button state should match backend approval gates.
If backend rejects approval, the UI should show the backend reason and refresh
model data.

### Validation And Field Types

Forms should use appropriate inputs:

- date/time pickers with from-before-to validation;
- number fields with safe min/max and step values;
- checkbox/toggle for booleans;
- segmented controls for workflow mode;
- selectors for entities;
- copy buttons and truncation for long ids/hashes.

Errors should name the field and explain how to fix it.

## Risks

- Loading metrics for many selected devices can be slow. Cache per device and
  request only summaries where possible.
- Draft persistence can become stale when backend entities are deleted. Draft
  restore must validate referenced entities before use.
- More guided UI can hide advanced controls. Provide an advanced section rather
  than removing advanced ids and JSON entirely.
