## ADDED Requirements

### Requirement: External imports create dataset versions

CSV and JSONL imports SHALL create account-scoped ML dataset versions that can
be validated, inspected, and later materialized into canonical training rows.

#### Scenario: CSV import creates a validating dataset version

- **GIVEN** an authorized operator uploads a CSV file with a valid schema mapping
- **AND** at least one row is accepted
- **WHEN** the import completes
- **THEN** the system SHALL create a dataset version linked to the imported file
- **AND** the dataset version SHALL be marked `VALIDATING`
- **AND** the dataset version SHALL not be marked `READY` until accepted canonical rows exist
- **AND** the validation report SHALL be linked to the dataset version
- **AND** the dataset version SHALL be returned by dataset listing APIs.

#### Scenario: Import rejects every row

- **GIVEN** an import contains no accepted canonical rows
- **WHEN** the import completes
- **THEN** the system SHALL create no `READY` dataset version
- **AND** the operator SHALL receive validation reasons explaining why the file cannot be used for training.

### Requirement: Internal telemetry dataset versions are immutable

Internal telemetry dataset creation SHALL freeze the selected telemetry rows or
store an equivalent immutable artifact reference before marking the dataset
version `READY`.

#### Scenario: Internal telemetry selection is materialized

- **GIVEN** selected devices, metrics, and time range pass validation
- **WHEN** the dataset version is created
- **THEN** the system SHALL materialize canonical ML rows for that selection
- **AND** store an artifact URI or immutable row reference
- **AND** store a deterministic data hash
- **AND** mark the dataset version `READY`.

#### Scenario: Materialization fails

- **GIVEN** telemetry validation succeeds
- **BUT** row materialization or artifact persistence fails
- **WHEN** dataset creation completes
- **THEN** the dataset version SHALL not be marked `READY`
- **AND** a sanitized failure reason SHALL be stored.

### Requirement: Dataset versions expose data quality profiles

Dataset versions SHALL expose enough quality metadata for an operator to decide
whether the data is suitable for model training.

#### Scenario: Operator views dataset detail

- **GIVEN** a dataset version exists
- **WHEN** the operator opens dataset detail
- **THEN** the response SHALL include row count, device count, metric count, time range, coverage, sampling gaps, rejected rows, warnings, and lineage
- **AND** the response SHALL include artifact/hash metadata when available.

### Requirement: Imported device mappings are account-safe

Imported rows that claim an existing device id SHALL be verified against account
ownership before they are accepted as matched-device training data.

#### Scenario: Imported device id belongs to another account

- **GIVEN** a CSV or JSONL row maps to an existing device id
- **AND** the device is not owned by the importing account
- **WHEN** the row is validated
- **THEN** the row SHALL be rejected or quarantined
- **AND** the validation report SHALL include an ownership failure reason.

### Requirement: Internal dataset creation is available to operators

The frontend SHALL provide an operator workflow for creating internal telemetry
dataset versions.

#### Scenario: Operator creates internal telemetry dataset from UI

- **GIVEN** the operator can access selected devices
- **WHEN** the operator selects metrics, time range, and quality gates
- **THEN** the frontend SHALL call the internal telemetry dataset API
- **AND** show resulting status, validation summary, lineage, and artifact/hash information.
