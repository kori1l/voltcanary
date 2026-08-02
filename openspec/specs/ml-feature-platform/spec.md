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

