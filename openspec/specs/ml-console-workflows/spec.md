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

