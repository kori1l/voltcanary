## ADDED Requirements

### Requirement: ML-service training execution is idempotent per training run

ML-service SHALL make training execution duplicate-safe for retries of the same
analytics-owned training run.

#### Scenario: Worker retries the same training run

- **GIVEN** analytics-service retries a training request with the same
  `trainingRunId` and idempotency key
- **WHEN** ML-service has already produced a verified artifact for that request
- **THEN** ML-service SHALL return the existing artifact metadata
- **AND** SHALL NOT create a duplicate model bundle.

#### Scenario: Conflicting retry is submitted

- **GIVEN** ML-service has recorded an idempotency key for a training run
- **WHEN** a retry uses the same key with different dataset, feature snapshot,
  model family, algorithm, horizon, or hyperparameters
- **THEN** ML-service SHALL reject the request as an idempotency conflict
- **AND** SHALL NOT overwrite the existing artifact metadata.

### Requirement: ML-service training supports cooperative cancellation

ML-service SHALL stop training before final artifact promotion when the
analytics-owned run is canceled before a cancellation checkpoint completes.

#### Scenario: Cancellation arrives before artifact promotion

- **GIVEN** a training run is executing in ML-service
- **WHEN** analytics-service marks the run cancellation requested
- **THEN** ML-service SHALL observe cancellation at a checkpoint
- **AND** return a canceled or failed-with-cancellation response
- **AND** no candidate-ready artifact SHALL be promoted.

### Requirement: Partial training artifacts are not promoted

ML-service SHALL avoid exposing incomplete model bundles after failed,
timed-out, or canceled training.

#### Scenario: Artifact write fails midway

- **GIVEN** model fitting succeeds
- **BUT** artifact persistence fails before all bundle files and hashes are
  written
- **WHEN** ML-service returns the training response
- **THEN** the response SHALL be failed
- **AND** no final manifest URI SHALL point at an incomplete bundle.
