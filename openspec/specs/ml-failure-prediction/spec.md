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

### Requirement: Failure model cards use backend evaluation metrics consistently

The frontend SHALL display failure-prediction model metrics and approval gates
from the same metric contract used by backend model approval.

#### Scenario: Backend returns evaluation metrics as JSON

- **GIVEN** a trained failure model response includes evaluation metrics as
  `evaluationMetricsJson`
- **WHEN** the model registry renders the model card
- **THEN** the frontend SHALL parse the JSON into a metrics object
- **AND** show available precision, recall, F1, PR-AUC, label coverage,
  positive labels, negative labels, false-alert rate, lead time, and load-test
  status instead of `N/A`.

#### Scenario: Candidate model is approvable by backend

- **GIVEN** backend approval would accept a candidate model
- **WHEN** the frontend renders the model registry
- **THEN** the Approve action SHALL be enabled
- **AND** frontend promotion-gate messages SHALL not contradict backend
  approval behavior.

#### Scenario: Backend rejects approval

- **GIVEN** backend approval rejects a candidate model
- **WHEN** the operator clicks Approve
- **THEN** the UI SHALL show the backend rejection reason in operator-readable
  text
- **AND** refresh the model card state.

### Requirement: Failure training form validates against selected prerequisites

The failure-prediction training form SHALL validate the selected dataset,
feature snapshot, labels, and gates before queueing a training run.

#### Scenario: Operator configures failure training

- **GIVEN** the operator selects a dataset and feature snapshot
- **WHEN** the model family is `FAILURE_PREDICTION`
- **THEN** the UI SHALL require matching dataset version, matching horizon,
  positive label count, and minimum label coverage
- **AND** show the selected algorithm and gate thresholds before queueing.

### Requirement: Failure-prediction dataset readiness is previewed before commit

The dataset workbench SHALL preview failure-prediction label readiness for the
selected horizon before committing an internal telemetry dataset version.

#### Scenario: Operator previews failure-prediction readiness

- **GIVEN** the operator selects `FAILURE_PREDICTION`
- **AND** provides a positive horizon
- **WHEN** dataset preview evaluates the selected device scope, metrics, and
  time range
- **THEN** the response SHALL include positive, normal, unresolved,
  maintenance-excluded, and unobservable label counts
- **AND** include label coverage status and threshold decisions.

#### Scenario: Failure horizon is invalid

- **GIVEN** the operator selects `FAILURE_PREDICTION`
- **WHEN** the horizon is missing or not positive
- **THEN** the workbench preview SHALL report a blocking reason
- **AND** the UI SHALL not allow dataset creation from that preview.

#### Scenario: Preview has insufficient positive labels

- **GIVEN** supervised failure training requires positive outcomes
- **WHEN** preview finds no confirmed failure or corrective maintenance labels
  for the selected horizon
- **THEN** the preview SHALL be blocked or warning according to configured gates
- **AND** explain that supervised failure training is not ready for the
  selection.

#### Scenario: Preview does not mutate generated labels

- **GIVEN** existing generated labels may exist for the dataset or account
- **WHEN** failure readiness preview runs
- **THEN** preview SHALL not create, delete, or replace generated labels
- **AND** explicit horizon-label generation SHALL remain a separate
  lineage-changing action.

