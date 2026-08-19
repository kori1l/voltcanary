# ml-failure-prediction Specification

## ADDED Requirements

### Requirement: Failure Training Gates Are Independently Testable

Failure-prediction training SHALL keep label sufficiency, model fitting,
evaluation metrics, approval gate construction, and model card generation in
separately testable components.

#### Scenario: Approval thresholds change

- **WHEN** a failure-prediction approval profile is adjusted
- **THEN** approval gate evaluation SHALL be testable without running full
  model fitting or artifact persistence
- **AND** training responses SHALL still include gate details required by
  analytics-service and the frontend.

#### Scenario: Failure model card changes

- **WHEN** model card metadata changes
- **THEN** model card assembly SHALL be covered independently from artifact
  provider I/O
- **AND** the resulting training response SHALL preserve existing failure-risk
  fields and aliases.
