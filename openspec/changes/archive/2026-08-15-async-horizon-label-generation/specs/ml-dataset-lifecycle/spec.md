## ADDED Requirements

### Requirement: Dataset lineage references horizon label generation jobs

Dataset label readiness and downstream failure-prediction workflows SHALL be
able to reference asynchronous horizon label generation job results.

#### Scenario: Dataset detail has completed generated labels

- **GIVEN** a completed horizon label generation job exists for a dataset
  version
- **WHEN** an operator reviews dataset label readiness or downstream training
  prerequisites
- **THEN** the system SHALL use the completed job's generated label lineage and
  counts when reporting supervised failure-prediction readiness.

#### Scenario: Dataset has active label generation

- **GIVEN** a horizon label generation job is `QUEUED` or `RUNNING` for a
  dataset version
- **WHEN** the operator reviews dataset label readiness
- **THEN** the UI SHALL indicate that label generation is in progress
- **AND** downstream supervised training SHALL remain blocked until a completed
  label set satisfies readiness gates.
