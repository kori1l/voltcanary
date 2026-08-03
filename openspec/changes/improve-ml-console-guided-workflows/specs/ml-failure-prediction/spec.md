## ADDED Requirements

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
