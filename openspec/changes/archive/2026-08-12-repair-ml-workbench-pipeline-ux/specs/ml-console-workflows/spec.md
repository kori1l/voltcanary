## ADDED Requirements

### Requirement: ML console presents a pipeline-first workflow

The ML Settings frontend SHALL show anomaly-detection and failure-prediction
workflows as a readable lifecycle pipeline before detailed forms.

#### Scenario: Operator opens ML Settings

- **GIVEN** an operator opens ML Settings
- **WHEN** account ML state has loaded
- **THEN** the UI SHALL show a workflow mode control for anomaly detection and
  failure prediction
- **AND** the UI SHALL show pipeline steps for dataset, labels, feature
  snapshot, training, model approval, policy, and monitoring
- **AND** each step SHALL show whether the current workflow is ready, blocked,
  or needs review.

#### Scenario: Operator switches workflow mode

- **GIVEN** the operator switches from anomaly detection to failure prediction
- **WHEN** the pipeline is recalculated
- **THEN** label and horizon readiness SHALL become visible required steps
- **AND** anomaly-only prerequisites SHALL not be shown as failure-prediction
  requirements.

### Requirement: Dataset Workbench discovers preview data automatically

The Dataset Workbench SHALL automatically request a backend preview after the
operator has selected a valid scope and range.

#### Scenario: Operator selects a device

- **GIVEN** the Dataset Workbench has a valid date range
- **WHEN** the operator selects at least one device
- **THEN** preview state SHALL move to loading without requiring the operator to
  find a separate preview button
- **AND** metric coverage, candidate windows, timeline, and readiness SHALL
  populate from the preview response.

#### Scenario: Preview becomes stale

- **GIVEN** a fresh preview is visible
- **WHEN** the operator changes devices, metrics, model family, horizon, time
  range, split strategy, feature config, or quality gates
- **THEN** the UI SHALL keep create disabled
- **AND** schedule or offer a fresh preview before create can proceed.

### Requirement: Dataset Workbench device scope is readable

The Dataset Workbench SHALL let operators select device cohorts through readable
searchable rows rather than a native multi-select list.

#### Scenario: Operator selects a cohort

- **GIVEN** devices are available
- **WHEN** the operator searches and toggles devices
- **THEN** each selectable row SHALL show name, type, status, telemetry state,
  and id context
- **AND** the selected cohort summary SHALL stay visible.

### Requirement: Dataset Workbench timeline explains ML evidence

The Dataset Workbench telemetry timeline SHALL explain candidate windows,
coverage gaps, maintenance exclusions, and failure evidence using readable
visual layers.

#### Scenario: Preview returns timeline events

- **GIVEN** backend preview returns timeline buckets with maintenance or failure
  events
- **WHEN** the timeline renders
- **THEN** the chart SHALL show visually distinct event markers
- **AND** a legend SHALL identify candidate windows, selected range, gaps,
  maintenance, and failure evidence.
