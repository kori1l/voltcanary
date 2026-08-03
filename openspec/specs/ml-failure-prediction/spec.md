# ml-failure-prediction Specification

## Purpose
Define supervised, horizon-based equipment failure prediction behavior,
including label sufficiency gates, time-aware evaluation, explainable inference
outputs, and separation between ML failure risk and rule-based operational risk.
## Requirements
### Requirement: Failure prediction is horizon-based

The system SHALL model equipment failure risk as probability or calibrated risk
of confirmed failure or corrective maintenance within a configured future
horizon.

#### Scenario: Operator trains a 14-day failure model

- **GIVEN** a feature snapshot contains outcome-derived labels for a 14-day horizon
- **WHEN** the operator starts `FAILURE_PREDICTION` training
- **THEN** the model target SHALL be failure or corrective maintenance within 14 days
- **AND** the training run SHALL store the horizon in metrics and model metadata.

### Requirement: Failure models use time-based evaluation

Failure-prediction model evaluation SHALL use time-aware splits and
horizon-specific metrics.

#### Scenario: Candidate model is evaluated

- **GIVEN** a failure-prediction training run succeeds
- **WHEN** evaluation metrics are stored
- **THEN** the report SHALL include precision, recall, F1, PR-AUC or equivalent imbalanced metric, false alerts per device period, and lead-time metrics
- **AND** the report SHALL compare the candidate against baseline behavior.

### Requirement: Failure prediction output is additive and explainable

ML inference SHALL preserve existing prediction fields while adding
failure-specific risk fields.

#### Scenario: Active failure model scores a device

- **GIVEN** an active compatible failure model is selected
- **WHEN** live inference succeeds
- **THEN** the response SHALL include `failureRiskScore`, `failureHorizonDays`, `failureRiskBand`, confidence, and risk drivers
- **AND** existing `anomalyScore`, `degradationScore`, `riskLevel`, signals, and explanations SHALL remain present.

### Requirement: Insufficient labels block supervised failure training

The system SHALL not train or approve supervised failure models when label
coverage is below configured minimum gates.

#### Scenario: Dataset has no positive failures

- **GIVEN** a dataset has insufficient confirmed failure or corrective maintenance outcomes
- **WHEN** supervised failure training is requested
- **THEN** the training run SHALL fail or be rejected before fitting
- **AND** the failure reason SHALL identify insufficient positive labels.

### Requirement: Failure risk is separate from rule risk

Failure-prediction outputs SHALL not silently reuse final rule-based risk score
as the ML target or primary model feature.

#### Scenario: Rule risk is available during inference

- **GIVEN** analytics has computed rule-based risk
- **WHEN** ML failure inference is requested
- **THEN** the final rule risk score SHALL be recorded separately from ML failure risk
- **AND** any rule-derived model features SHALL have explicit versioned provenance.

### Requirement: Failure prediction production readiness is auditable

The system SHALL maintain an operator-readable audit status showing which
failure-prediction production blockers are implemented, which are partially
implemented, and which remain open.

#### Scenario: Engineer reviews production readiness

- **GIVEN** the ML failure prediction production audit exists
- **WHEN** an engineer reviews the current implementation state
- **THEN** the audit status SHALL map each major blocker to implemented evidence
  or remaining work
- **AND** implemented evidence SHALL reference archived OpenSpec changes,
  relevant tests, or concrete runtime verification.

### Requirement: Failure prediction lifecycle has repeatable production smoke coverage

The system SHALL provide a repeatable verification path for the complete
failure-prediction lifecycle in the local Docker environment.

#### Scenario: Data engineer verifies a failure model lifecycle

- **GIVEN** the Docker application profile is running
- **WHEN** the verification scenario creates or imports training data, generates
  features and horizon labels, trains a failure model, reviews approval gates,
  deploys the model, and recalculates live prediction
- **THEN** the scenario SHALL prove that live inference used or explicitly
  rejected the selected `modelVersionId`
- **AND** persisted traceability SHALL include model id, feature hash, input
  hash, policy source, fallback reason when applicable, and failure-risk fields
- **AND** monitoring signals SHALL expose fallback, error, canary or shadow, and
  quality outcomes when labels exist.

### Requirement: ML Settings supports production operator workflows

The ML Settings frontend SHALL present ML lifecycle controls in stable,
responsive layouts with readable errors.

#### Scenario: Operator uses ML Settings workflow tabs

- **GIVEN** the operator opens dataset, label, training, model, policy, or
  monitoring tabs
- **WHEN** forms contain multiple controls, long identifiers, or backend errors
- **THEN** controls SHALL remain aligned without label/input overlap
- **AND** long identifiers SHALL wrap or truncate within their containers
- **AND** API failures SHALL be shown as concise operator-readable messages
  rather than raw JSON response bodies.
