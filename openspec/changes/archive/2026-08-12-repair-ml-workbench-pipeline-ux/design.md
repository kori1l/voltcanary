# Design

## Overview

This change repairs the acceptance gap between the ML dataset workbench
requirements and the current UI. The workbench must present the ML lifecycle as
an operational pipeline first, then expose detailed dataset controls below that
pipeline.

## Frontend Pipeline Layout

`MlSettingsPage` owns a workflow state:

- `ANOMALY_DETECTION`
- `FAILURE_PREDICTION`

The top of ML Settings renders a compact pipeline rail:

```text
 Workflow mode: [Anomaly detection] [Failure prediction]
 Dataset -> Labels -> Feature snapshot -> Training -> Model -> Policy -> Monitoring
```

Each step shows status from current account state:

- `Ready`: enough saved state exists to continue.
- `Review`: data exists but has warnings or the current workflow requires
  another check.
- `Blocked`: required prerequisite is absent.

The rail is a navigation and context aid; existing tabs remain, but their order
and copy are scoped by the selected workflow.

## Dataset Workbench

The first viewport of the Datasets tab should show:

- pipeline state;
- workbench controls;
- device scope;
- timeline;
- readiness.

The metric matrix and commit controls remain below those panels. The preview
button stays available for retry, but discovery is automatic when:

- at least one device is selected;
- range is valid;
- failure mode has a positive horizon.

Preview requests are debounced and version guarded. Any change to scope,
metrics, range, family, horizon, split, feature config, or quality gates marks
the current result stale and schedules a fresh preview when the basic request is
valid.

## Device Scope Selector

The selector becomes a searchable checklist instead of a native multi-select.
Rows show name, type, status, location, telemetry state, and a shortened id.
Operators can select one or many devices with checkboxes. Search and filters
remain available.

## Timeline

The timeline remains a lightweight SVG, but it must be readable:

- empty state before preview explains the next automatic action;
- loading/stale states preserve the last chart while showing status;
- candidate bands show status and are clickable;
- selected range is visible;
- gap markers come from `NO_DATA` buckets or low coverage;
- event markers distinguish maintenance and failure/corrective evidence;
- a legend identifies colors and marker meanings.

The backend preview timeline will add event strings such as:

- `MAINTENANCE_EXCLUDED`
- `MAINTENANCE_OVERLAP`
- `CONFIRMED_FAILURE`
- `CORRECTIVE_MAINTENANCE`
- `UNRESOLVED_ANOMALY`
- `SENSOR_FAULT`

The frontend treats event strings as display data, not instructions.

## Downstream Readiness

Dataset Workbench and the page-level pipeline rail use the same state sources:

- datasets;
- labels;
- feature snapshot;
- training runs;
- models;
- policies;
- latest prediction metadata.

Failure prediction marks Labels and Feature snapshot as required. Anomaly
detection does not require horizon labels.

## Testing

Frontend tests should cover:

- automatic preview after device selection;
- stale state schedules refresh and keeps create disabled until fresh preview;
- checklist device selector selection and search;
- timeline event markers and candidate list selection;
- pipeline step status for failure prediction and anomaly detection.

Backend tests should cover:

- preview timeline includes maintenance/failure event markers;
- timeline still returns usable buckets when no events exist.
