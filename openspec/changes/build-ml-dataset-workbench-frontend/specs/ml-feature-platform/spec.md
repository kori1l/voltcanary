## ADDED Requirements

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
