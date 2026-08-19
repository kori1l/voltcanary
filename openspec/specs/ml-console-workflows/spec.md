# ml-console-workflows Specification

## Purpose
TBD - created by archiving change improve-ml-console-guided-workflows. Update Purpose after archive.
## Requirements
### Requirement: ML console provides guided workflow modes

The ML Settings console SHALL guide operators through distinct anomaly-detection
and failure-prediction workflows instead of presenting one mixed collection of
backend forms.

#### Scenario: Operator selects failure prediction

- **GIVEN** an operator opens ML Settings
- **WHEN** the operator selects the failure-prediction workflow
- **THEN** the UI SHALL show the required sequence of dataset, horizon labels,
  failure feature snapshot, training, model approval, policy, and monitoring
- **AND** each step SHALL indicate whether the current account has enough saved
  state to continue
- **AND** anomaly-only instructions SHALL not be shown as prerequisites for
  failure-prediction training.

#### Scenario: Operator selects anomaly detection

- **GIVEN** an operator opens ML Settings
- **WHEN** the operator selects the anomaly-detection workflow
- **THEN** the UI SHALL omit supervised label requirements
- **AND** feature snapshot and training defaults SHALL use anomaly-detection
  configuration.

### Requirement: ML console uses entity selectors instead of raw id entry

The frontend SHALL allow operators to choose devices, device types, metrics,
datasets, feature snapshots, models, and policies from searchable selectors
without requiring them to find and paste raw UUIDs.

#### Scenario: Operator creates an internal telemetry dataset

- **GIVEN** the operator has accessible devices with telemetry
- **WHEN** the operator creates an internal dataset
- **THEN** the UI SHALL provide a multi-select of devices showing name, type,
  status, and id
- **AND** the UI SHALL provide a metric selector populated from selected devices
- **AND** the request SHALL still submit canonical device ids and metric names
  to the backend.

#### Scenario: Operator deploys a model to one device

- **GIVEN** an approved trained model exists
- **WHEN** the operator creates a device-scoped deployment policy
- **THEN** the model SHALL be selected from a deployable model selector
- **AND** the device SHALL be selected from a device selector
- **AND** the UI SHALL prevent a model id from being entered as the device id.

### Requirement: ML console preserves unsaved drafts

The ML Settings frontend SHALL preserve unsaved form drafts while operators move
between tabs or reload the page.

#### Scenario: Operator switches tabs while editing a dataset form

- **GIVEN** the operator has filled device, metric, date range, and quality-gate
  fields but has not submitted the dataset form
- **WHEN** the operator switches to another ML Settings tab and returns
- **THEN** the draft values SHALL be restored
- **AND** the UI SHALL mark restored values that reference deleted or
  inaccessible entities.

#### Scenario: Operator submits a restored draft

- **GIVEN** a restored draft references valid current entities
- **WHEN** the operator successfully submits the form
- **THEN** the saved draft for that form SHALL be cleared.

### Requirement: ML console validates operator inputs before submission

The ML Settings frontend SHALL catch common field mistakes before calling ML
APIs.

#### Scenario: Operator enters an invalid time range

- **GIVEN** an operator creates a dataset
- **WHEN** the start time is after the end time
- **THEN** the UI SHALL block submission
- **AND** show a field-level error explaining that `From` must be before `To`.

#### Scenario: Operator selects a mismatched failure horizon

- **GIVEN** a dataset has generated 7-day horizon labels
- **AND** a feature snapshot is generated for a different horizon
- **WHEN** the operator attempts failure-prediction training
- **THEN** the UI SHALL warn or block until the label, feature snapshot, and
  training horizons match.

### Requirement: ML console provides a Dataset Workbench for internal telemetry

The ML Settings dataset workflow SHALL provide a production Dataset Workbench
that lets operators discover telemetry, inspect candidate windows, preview
readiness, and commit immutable internal telemetry dataset versions.

#### Scenario: Operator opens the dataset workbench

- **GIVEN** an operator opens the ML Settings dataset workflow
- **WHEN** internal telemetry dataset creation is selected
- **THEN** the UI SHALL show a workbench with workflow mode, device scope,
  telemetry timeline, metric coverage matrix, readiness panel, and dataset
  commit controls
- **AND** the UI SHALL not present the workflow as a raw backend form.

#### Scenario: Operator selects device scope

- **GIVEN** the operator has accessible devices
- **WHEN** the operator searches, filters, and selects one or more devices
- **THEN** the UI SHALL show selected device count, active/inactive counts, and
  telemetry availability
- **AND** submit canonical device ids to the backend.

#### Scenario: Operator reviews real metric availability

- **GIVEN** one or more devices are selected
- **WHEN** preview data is available
- **THEN** the metric selector SHALL show only metrics present on selected
  devices or in selected telemetry scope
- **AND** common-metric mode SHALL show the intersection of selected-device
  metrics
- **AND** all-available mode SHALL show metric coverage across selected devices
- **AND** phantom fallback metrics SHALL not be selectable.

#### Scenario: Operator selects a recommended time window

- **GIVEN** backend preview returned candidate windows
- **WHEN** the operator chooses a recommended candidate
- **THEN** the UI SHALL update `from` and `to` from that candidate
- **AND** show why the window is ready, warning, or blocked.

#### Scenario: Operator adjusts time range visually

- **GIVEN** the telemetry timeline is visible
- **WHEN** the operator adjusts the brush selection
- **THEN** the exact datetime fields SHALL update to the selected range
- **AND** the workbench SHALL request a fresh backend preview before enabling
  dataset creation.

#### Scenario: Preview blocks dataset creation

- **GIVEN** the backend preview status is `BLOCKED`
- **WHEN** the operator reviews the workbench
- **THEN** the create action SHALL be disabled
- **AND** the readiness panel SHALL show field-level blocking reasons.

#### Scenario: Preview is stale

- **GIVEN** a preview has been returned
- **WHEN** the operator changes devices, metrics, model family, horizon, time
  range, split strategy, feature config, or quality gates
- **THEN** the UI SHALL mark preview state as stale
- **AND** dataset creation SHALL remain disabled until a fresh preview is
  available or the backend revalidates successfully.

### Requirement: Dataset Workbench is accessible and responsive

The Dataset Workbench SHALL remain usable with keyboard input, screen-reader
accessible status text, and mobile or narrow desktop layouts.

#### Scenario: Operator cannot use pointer drag

- **GIVEN** candidate windows are available
- **WHEN** the operator uses keyboard controls
- **THEN** the operator SHALL be able to select a candidate window from the
  candidate list
- **AND** edit exact datetime fields as the accessible equivalent of brush
  selection.

#### Scenario: Workbench is viewed on a narrow screen

- **GIVEN** the workbench contains scope, timeline, readiness, metric matrix,
  and commit controls
- **WHEN** the viewport is narrow
- **THEN** the UI SHALL stack sections without overlapping text or controls
- **AND** keep metric status and create blocking reasons readable.

#### Scenario: Preview dependency fails

- **GIVEN** the preview request fails because an API dependency is unavailable
- **WHEN** the UI renders the error
- **THEN** the workbench SHALL show an operator-readable dependency error
- **AND** preserve current draft selections for retry.

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

### Requirement: ML console tracks long-running horizon label generation

The ML Settings Labels workflow SHALL present horizon label generation as a
status-tracked operation instead of surfacing gateway timeouts as user-facing
errors.

#### Scenario: Operator starts label generation

- **GIVEN** the operator selected a dataset version and horizon configuration
- **WHEN** the operator starts horizon label generation
- **THEN** the UI SHALL show the returned job status
- **AND** disable duplicate start actions while the job is `QUEUED` or
  `RUNNING`.

#### Scenario: Generation is still running

- **GIVEN** a horizon label generation job is `QUEUED` or `RUNNING`
- **WHEN** the Labels tab is visible
- **THEN** the UI SHALL poll or provide a clear check-status action
- **AND** keep the last known status visible if a status refresh temporarily
  fails.

#### Scenario: Generation completes

- **GIVEN** a horizon label generation job reaches `COMPLETED`
- **WHEN** the UI receives the completed status
- **THEN** it SHALL show generated, positive, normal, unobservable counts and
  lineage hash
- **AND** refresh the dataset label list for the selected dataset version.

#### Scenario: Generation fails

- **GIVEN** a horizon label generation job reaches `FAILED`
- **WHEN** the UI receives the failed status
- **THEN** it SHALL show the sanitized failure reason
- **AND** allow the operator to retry generation after adjusting inputs or
  dependencies.

#### Scenario: Gateway request times out

- **GIVEN** the browser or gateway times out while starting or polling a horizon
  label generation job
- **WHEN** the UI handles the timeout
- **THEN** it SHALL not show raw gateway endpoint errors as the primary workflow
  state
- **AND** it SHALL preserve any known job status and offer status refresh.

### Requirement: ML console preserves feature snapshot inventory

The ML Settings console SHALL show available feature snapshots and use that
inventory when queueing training.

#### Scenario: Operator generates multiple snapshots

- **GIVEN** the operator creates anomaly and failure-prediction feature
  snapshots
- **WHEN** the operator opens Training
- **THEN** the snapshot selector SHALL show compatible existing snapshots rather
  than only the most recently generated snapshot.

#### Scenario: Training has no compatible snapshot

- **GIVEN** no current snapshot matches the selected dataset, model family, and
  horizon
- **WHEN** the operator opens Training
- **THEN** the UI SHALL block queueing training
- **AND** explain that a feature snapshot must be generated or selected first.

### Requirement: Frontend E2E verifies ML operator workflows

The ML console SHALL have browser-level verification proving operators can run
the ML lifecycle and inspect downstream incidents.

#### Scenario: Operator trains models from the console

- **GIVEN** synthetic datasets and feature snapshots exist for anomaly detection
  and failure prediction
- **WHEN** browser E2E tests use the ML Settings console
- **THEN** the operator SHALL be able to queue anomaly-detection and
  failure-prediction training runs from the UI
- **AND** each run SHALL reach a terminal successful status or show an
  operator-readable failure.

#### Scenario: Operator inspects model-backed prediction

- **GIVEN** a trained model is active for a device
- **WHEN** the operator loads that device in ML Settings or device details
- **THEN** the UI SHALL show prediction status, model version id, model family,
  policy source, fallback reason, confidence, input hash, and available risk
  fields without layout overlap.

#### Scenario: Operator inspects ML-created incident

- **GIVEN** ML output has created an alert for a device
- **WHEN** the operator opens `/alerts` or the device alerts tab
- **THEN** the alert SHALL be visible with status, severity, type, device, and
  source trace fields supported by the API
- **AND** acknowledge and resolve actions SHALL update the UI after reload.

