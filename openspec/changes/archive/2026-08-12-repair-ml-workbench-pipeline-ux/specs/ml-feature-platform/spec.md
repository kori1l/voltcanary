## ADDED Requirements

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
