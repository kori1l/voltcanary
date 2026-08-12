# ml-feature-platform Specification

## Purpose
TBD - created by archiving change materialize-ml-feature-platform. Update Purpose after archive.
## Requirements
### Requirement: Feature snapshots contain materialized feature rows

Feature generation SHALL produce a loadable feature matrix for each `READY`
dataset version and selected feature configuration.

#### Scenario: Feature snapshot is generated for internal telemetry

- **GIVEN** a `READY` internal telemetry dataset version with materialized rows
- **WHEN** feature generation runs
- **THEN** the system SHALL produce feature rows for the configured windows
- **AND** store a feature artifact URI or immutable row reference
- **AND** store row count, rejected row count, feature schema hash, and feature data hash.

#### Scenario: Feature generation cannot produce required rows

- **GIVEN** a dataset version is missing required metrics or history
- **WHEN** feature generation runs
- **THEN** the feature snapshot SHALL not be usable for training
- **AND** the failure response SHALL explain the missing feature requirements.

### Requirement: Online and offline features use the same contract

Live inference SHALL use the same feature columns and transformations as the
feature snapshot used to train the selected model.

#### Scenario: Active model requires a known feature schema

- **GIVEN** an active model version references a feature schema hash
- **WHEN** analytics requests live inference
- **THEN** online feature generation SHALL produce that schema
- **OR** inference SHALL fall back with an explicit `FEATURE_SCHEMA_MISMATCH` or `FEATURES_UNAVAILABLE` status.

### Requirement: Feature hashes distinguish schema, config, and data

The system SHALL store separate hashes for feature schema, feature config, and
feature data.

#### Scenario: Feature values change while columns remain the same

- **GIVEN** the same feature columns are generated from different source rows
- **WHEN** feature snapshots are compared
- **THEN** the feature schema hash MAY match
- **AND** the feature data hash SHALL differ.

### Requirement: Device-type feature templates are versioned

Feature generation SHALL support versioned templates for generic and
device-type-specific models.

#### Scenario: Operator selects a device-type template

- **GIVEN** a dataset version contains supported device type metadata
- **WHEN** the operator generates features with a device-type template
- **THEN** generated features SHALL include template-specific columns
- **AND** the template version SHALL be stored in feature snapshot metadata.

### Requirement: Feature snapshots support safe cleanup

Feature snapshots SHALL support archive or delete actions with reference checks.

#### Scenario: Operator archives an obsolete feature snapshot

- **GIVEN** a feature snapshot is not needed in default selectors
- **WHEN** an authorized operator archives it with a reason
- **THEN** the snapshot SHALL be hidden from default feature snapshot selectors
- **AND** row counts, hashes, artifact references, and training lineage SHALL
  remain readable.

#### Scenario: Operator attempts to delete a model-referenced snapshot

- **GIVEN** a feature snapshot is referenced by a training run or model version
- **WHEN** an operator requests hard deletion
- **THEN** the system SHALL block deletion
- **AND** return the referencing training run or model ids.

#### Scenario: Operator deletes an unreferenced failed snapshot

- **GIVEN** a feature snapshot failed or was never referenced
- **WHEN** an authorized operator deletes it with a reason
- **THEN** it SHALL be removed from default feature snapshot lists
- **AND** the action SHALL be audit logged.

### Requirement: Feature snapshot UI exposes model-family configuration

The frontend SHALL expose the feature snapshot configuration required by each
model family supported by the feature generation API.

#### Scenario: Operator generates a failure-prediction feature snapshot

- **GIVEN** a `READY` dataset version has generated failure horizon labels
- **WHEN** the operator chooses model family `FAILURE_PREDICTION`
- **THEN** the UI SHALL require a positive `horizonDays` value
- **AND** include `modelFamily=FAILURE_PREDICTION` and that `horizonDays` value
  in the feature snapshot request
- **AND** show the created feature snapshot id, row count, rejected row count,
  schema hash, data hash, template id, template version, and horizon.

#### Scenario: Operator generates an anomaly feature snapshot

- **GIVEN** a `READY` dataset version exists
- **WHEN** the operator chooses model family `ANOMALY_DETECTION`
- **THEN** the UI SHALL not require horizon labels
- **AND** the created feature snapshot SHALL be labeled as anomaly-detection
  compatible.

### Requirement: Feature snapshot selectors expose compatibility

Feature snapshot selectors SHALL show enough metadata to prevent choosing an
incompatible snapshot for training.

#### Scenario: Operator starts failure-prediction training

- **GIVEN** both anomaly and failure feature snapshots exist for a dataset
- **WHEN** the operator opens the training form for `FAILURE_PREDICTION`
- **THEN** the feature snapshot selector SHALL prioritize snapshots with
  `modelFamily=FAILURE_PREDICTION`
- **AND** show the horizon, feature schema hash, row count, and created time
- **AND** warn when the selected snapshot horizon differs from the training
  horizon.

### Requirement: Dataset Workbench preserves feature and training compatibility

The Dataset Workbench SHALL carry selected model family, horizon, metrics, and
window intent into downstream feature snapshot and training defaults without
weakening existing compatibility checks.

#### Scenario: Workbench creates a failure-prediction dataset

- **GIVEN** the operator creates a dataset from a failure-prediction workbench
  preview
- **WHEN** the operator proceeds to feature snapshot generation
- **THEN** the UI SHALL default the feature snapshot model family to
  `FAILURE_PREDICTION`
- **AND** default `horizonDays` to the previewed horizon
- **AND** keep the horizon editable only when a fresh preview or label
  readiness check confirms compatibility.

#### Scenario: Workbench creates an anomaly dataset

- **GIVEN** the operator creates a dataset from an anomaly-detection workbench
  preview
- **WHEN** the operator proceeds to feature snapshot generation
- **THEN** the UI SHALL default the feature snapshot model family to
  `ANOMALY_DETECTION`
- **AND** not require horizon labels for anomaly feature snapshots.

#### Scenario: Training selector uses workbench lineage metadata

- **GIVEN** a dataset version and feature snapshot were created from workbench
  selections
- **WHEN** the operator opens training
- **THEN** feature snapshot selectors SHALL prioritize snapshots matching the
  selected dataset version, model family, horizon, and feature schema metadata
- **AND** block or warn on family or horizon mismatch before submitting the
  training run.

### Requirement: Workbench pipeline exposes downstream readiness

The Dataset Workbench and ML Settings pipeline SHALL make downstream feature
snapshot and training readiness visible before operators create or train from a
dataset.

#### Scenario: Failure-prediction workbench is selected

- **GIVEN** the operator chooses failure prediction
- **WHEN** the workbench renders readiness
- **THEN** downstream readiness SHALL show whether horizon labels, failure
  feature snapshots, matching training runs, model approval, deployment policy,
  and monitoring traces exist
- **AND** missing required steps SHALL be shown as blocked or review states
  rather than hidden behind later tabs.

#### Scenario: Anomaly workflow is selected

- **GIVEN** the operator chooses anomaly detection
- **WHEN** the workbench renders readiness
- **THEN** downstream readiness SHALL not require horizon labels
- **AND** feature snapshot and training readiness SHALL be scoped to anomaly
  model-family defaults.

