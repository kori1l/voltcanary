# Design

## Overview

Deliver the Dataset Workbench inside ML Settings as a focused, operational UI
for data engineers. It consumes the backend preview contract from
`build-ml-dataset-workbench` and renders the preview as selectors, timeline
overlays, metric coverage, readiness gates, and final create controls.

The workbench should be dense and scannable. It should not look like a landing
page or a decorative dashboard.

## Component Structure

Target structure:

```text
frontend/src/features/ml-datasets/
  components/DatasetWorkbench.tsx
  components/DeviceScopeSelector.tsx
  components/MetricCoverageMatrix.tsx
  components/TelemetryWindowChart.tsx
  components/CandidateWindowList.tsx
  components/DatasetReadinessPanel.tsx
  model/datasetWorkbench.types.ts
  model/datasetWorkbench.utils.ts
```

`MlSettingsPage.tsx` should own tab composition and high-level data loading.
Workbench-specific state and rendering should move into feature components when
the extraction reduces page complexity.

## Workbench State

State fields:

- `modelFamily`
- `horizonDays`
- `deviceScope`
- `deviceQuery`
- `deviceTypeFilter`
- `deviceStatusFilter`
- `metricSelectionMode`
- `selectedMetrics`
- `focusedMetric`
- `range`
- `quickRange`
- `selectedCandidateId`
- `datasetDraft`
- `preview`
- `previewStatus`: `idle`, `loading`, `ready`, `stale`, `error`
- `previewRequestVersion`

Rules:

- Changes to scope, metrics, range, family, horizon, split strategy, feature
  config version, or quality gates mark preview stale.
- A newer preview response must not be overwritten by an older in-flight
  request.
- Create is enabled only when preview is ready and not blocked.
- Restored drafts are reconciled against current devices and preview metrics.
- Stale restored devices or metrics are shown and block create until removed.

## Layout

Desktop layout:

- Header row: model family, horizon, quick range, preview status.
- Left column: device scope selector and selected cohort summary.
- Center column: telemetry timeline and candidate window list.
- Right column: readiness panel.
- Full-width lower area: metric coverage matrix and dataset commit controls.

Mobile layout:

- Stack header, scope, timeline, readiness, metric matrix, and commit controls.
- Controls must not overlap or truncate critical text.
- Metric matrix may become horizontally scrollable only if column labels remain
  visible and rows are still usable.

## Device Scope Selector

Required behavior:

- Search by device name, id, type, status, and location when available.
- Filter by type and status.
- Support one device or multiple selected devices.
- Show selected count, active/inactive count, telemetry-present count, and
  missing-telemetry count.
- Preserve selected devices in draft.
- Mark stale restored devices as unavailable.

The selector must not require raw UUID entry.

## Metric Coverage Matrix

The matrix renders preview-provided metrics only.

Rows include:

- display name and canonical metric name;
- unit;
- devices covered;
- coverage ratio;
- sample count;
- max gap;
- duplicate rate;
- non-finite or impossible value count;
- unit warnings;
- status;
- selected checkbox.

Modes:

- `Common metrics`: selectable metrics are present on every selected device.
- `All available with coverage`: metrics can be partial, but coverage and
  warnings are explicit.

No phantom fallback metric can be selectable. If selected devices expose no
metrics, show an empty state.

## Telemetry Timeline

The timeline explains candidate windows.

Layers:

- focused metric series;
- coverage/density band;
- candidate window overlays;
- selected range overlay;
- gap markers;
- maintenance exclusion overlays;
- failure/corrective-maintenance markers in failure-prediction mode.

Color semantics:

- green: ready/recommended;
- yellow: warning;
- red: blocked;
- blue: user-selected active range;
- purple or another distinct accent: failure or corrective-maintenance evidence;
- gray: excluded/unavailable interval.

Interactions:

- Hover shows timestamp, value, and quality state.
- Candidate band click selects the candidate.
- Brush selection updates `from` and `to`.
- Exact datetime fields update the chart selection.
- Candidate list remains the keyboard-accessible equivalent of clicking chart
  bands.

## Candidate Window List

Each candidate item shows:

- name;
- `from` and `to`;
- score;
- status;
- sample count;
- coverage;
- max gap;
- failure label counts when applicable;
- explanation;
- warnings or blocking reasons;
- `Use window` action.

Selecting a candidate updates the selected window and requests a fresh preview
when required.

## Readiness Panel

The readiness panel groups preview output:

- scope summary;
- data quality gates;
- metric coverage gates;
- failure label readiness;
- downstream feature/training compatibility;
- final decision.

Blocked create must always have field-level reasons. Warnings must be visible
but must not prevent create unless backend marks them blocking.

## Dataset Commit Controls

Commit controls include:

- dataset name;
- description;
- split strategy;
- feature config version;
- minimum samples per metric;
- maximum sampling gap seconds;
- final selection summary;
- create button.

Create payload includes selected devices, selected metrics, selected range,
split strategy, feature config version, quality gates, and preview id/hash when
available.

After successful create:

- clear the dataset draft;
- show dataset version id, status, and validation summary;
- route the next action according to model family:
  - anomaly: generate feature snapshot;
  - failure: generate or review horizon labels, then generate feature snapshot.

## Feature Snapshot And Training Continuity

When a dataset is created from workbench state:

- feature snapshot defaults use the workbench model family;
- failure snapshot defaults use the workbench horizon;
- training selectors prioritize snapshots matching dataset version, model
  family, horizon, schema hash, and row count;
- mismatches remain blocked or warned before submission.

## Accessibility

Required:

- Candidate windows can be selected without pointer input.
- Brush has equivalent exact datetime controls.
- Form controls have labels and error associations.
- Status colors are not the only source of meaning.
- Focus order follows the visual workflow.
- Dynamic preview status changes are announced through accessible text.

## Testing

Frontend tests should use existing Vitest/Testing Library patterns. Add
Playwright or browser screenshot checks only if the project already adopts them
or the implementation introduces chart behavior that unit tests cannot validate
reliably.

Required coverage:

- device search and cohort selection;
- stale device draft handling;
- metrics rendered only from preview;
- no selectable default fallback metrics;
- common and all-available modes;
- candidate selection;
- brush or equivalent range selection;
- stale preview state;
- loading and dependency-error preview states;
- blocked readiness disables create and renders reasons;
- failure horizon readiness;
- create payload with preview fields;
- next-step defaults for feature snapshot and training.
