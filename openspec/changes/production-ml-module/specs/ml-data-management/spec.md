## ADDED Requirements

### Requirement: Internal telemetry can become versioned ML training data

The system SHALL allow an authorized operator to create an immutable dataset
version from stored telemetry using account, device, device type, metric, and
time window filters.

#### Scenario: Operator creates a dataset from stored telemetry

- **GIVEN** the account has stored telemetry for selected devices
- **WHEN** the operator creates a dataset version from those devices and a time range
- **THEN** the system validates telemetry coverage and metric support
- **AND** stores dataset lineage with the query filters, selected devices, metrics, and source time range
- **AND** marks the dataset version as `READY` only after validation succeeds

#### Scenario: Selected telemetry is not sufficient for training

- **GIVEN** the selected telemetry window contains too few samples or unsupported metrics
- **WHEN** the operator validates the dataset
- **THEN** the dataset version is not marked `READY`
- **AND** the validation report explains the missing coverage or unsupported metrics

### Requirement: External datasets can be imported for training

The system SHALL support importing external datasets into account-scoped ML
datasets without mixing raw imported rows into live telemetry tables.

#### Scenario: Operator imports a CSV dataset

- **GIVEN** the operator uploads a CSV file and defines a schema mapping
- **WHEN** the import runs
- **THEN** the system validates timestamp, device key, metric name, metric value, unit, label, and metadata fields
- **AND** stores the original content hash, mapping version, validation report, and import provenance
- **AND** makes accepted rows available for dataset version creation

#### Scenario: External dataset contains invalid rows

- **GIVEN** an imported file contains invalid timestamps, non-finite values, or unsupported metric names
- **WHEN** the import validation runs
- **THEN** invalid rows are rejected
- **AND** accepted rows are still retained when the import policy allows partial acceptance
- **AND** the validation report contains rejected row counts and reasons

### Requirement: Labels are managed as auditable training inputs

The system SHALL support labels from manual review, imported datasets, alert
outcomes, and maintenance outcomes.

#### Scenario: Operator corrects a label

- **GIVEN** a dataset row or time window has an existing label
- **WHEN** an authorized operator changes the label
- **THEN** the new label is stored
- **AND** the previous label, editor, timestamp, and reason are retained in audit history

### Requirement: Feature snapshots are reproducible

Feature generation SHALL produce versioned snapshots whose inputs and
configuration can be reproduced.

#### Scenario: Training starts from a dataset version

- **GIVEN** a `READY` dataset version and a feature configuration
- **WHEN** feature generation runs
- **THEN** the system stores a feature snapshot with dataset version id, feature config version, feature schema hash, row count, rejected row count, and artifact reference
- **AND** repeating the same generation inputs produces the same feature schema hash

