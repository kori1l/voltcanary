## ADDED Requirements

### Requirement: Feature snapshot UI exposes model-family configuration

The frontend SHALL expose the feature snapshot configuration required by each
model family supported by the feature generation API.

#### Scenario: Operator generates a failure-prediction feature snapshot

- **GIVEN** a `READY` dataset version has generated failure horizon labels
- **WHEN** the operator chooses model family `FAILURE_PREDICTION`
- **THEN** the UI SHALL require a positive `horizonDays` value
- **AND** include `modelFamily=FAILURE_PREDICTION` and that `horizonDays` value
  in the feature snapshot request
- **AND** show the created feature snapshot id, row count, rejected row count,
  schema hash, data hash, template id, template version, and horizon.

#### Scenario: Operator generates an anomaly feature snapshot

- **GIVEN** a `READY` dataset version exists
- **WHEN** the operator chooses model family `ANOMALY_DETECTION`
- **THEN** the UI SHALL not require horizon labels
- **AND** the created feature snapshot SHALL be labeled as anomaly-detection
  compatible.

### Requirement: Feature snapshot selectors expose compatibility

Feature snapshot selectors SHALL show enough metadata to prevent choosing an
incompatible snapshot for training.

#### Scenario: Operator starts failure-prediction training

- **GIVEN** both anomaly and failure feature snapshots exist for a dataset
- **WHEN** the operator opens the training form for `FAILURE_PREDICTION`
- **THEN** the feature snapshot selector SHALL prioritize snapshots with
  `modelFamily=FAILURE_PREDICTION`
- **AND** show the horizon, feature schema hash, row count, and created time
- **AND** warn when the selected snapshot horizon differs from the training
  horizon.
