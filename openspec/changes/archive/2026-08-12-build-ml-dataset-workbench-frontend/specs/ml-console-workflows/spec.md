## ADDED Requirements

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
