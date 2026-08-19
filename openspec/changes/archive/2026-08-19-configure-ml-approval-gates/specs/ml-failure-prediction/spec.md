## ADDED Requirements

### Requirement: Failure model approval gates are configurable and enforced

Failure-prediction approval gates SHALL use explicit threshold profiles rather
than hardcoded permissive thresholds, and backend approval SHALL enforce failed
required gates.

#### Scenario: Training uses a configured gate profile

- **GIVEN** a failure-prediction training run starts with a resolved approval
  gate profile
- **WHEN** ML-service evaluates the candidate model
- **THEN** approval metadata SHALL include the profile id and version
- **AND** each required gate SHALL include observed value, threshold, pass/fail
  state, and reason.

#### Scenario: Candidate fails required quality gates

- **GIVEN** a candidate failure model has precision, recall, PR-AUC, false-alert
  rate, label coverage, or load-test gates below the configured profile
- **WHEN** an operator attempts to approve the model
- **THEN** analytics-service SHALL reject approval
- **AND** return the failed gate names and operator-readable reasons.

#### Scenario: Candidate is missing approval profile metadata

- **GIVEN** a candidate failure model lacks required approval gate profile
  metadata
- **WHEN** an operator attempts to approve the model
- **THEN** analytics-service SHALL block approval
- **AND** explain that the model must be retrained or repaired with auditable
  gate metadata.
