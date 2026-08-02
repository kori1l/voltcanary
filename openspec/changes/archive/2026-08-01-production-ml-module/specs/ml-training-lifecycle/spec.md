## ADDED Requirements

### Requirement: Training runs are asynchronous and traceable

The system SHALL run model training asynchronously and persist status,
configuration, metrics, artifacts, and failure details for every training run.

#### Scenario: Operator starts anomaly model training

- **GIVEN** a `READY` dataset version and compatible feature snapshot
- **WHEN** an authorized operator starts an anomaly detection training run
- **THEN** the system creates a training run with status `QUEUED`
- **AND** a worker eventually moves it through `RUNNING` to `SUCCEEDED` or `FAILED`
- **AND** the final run stores model family, algorithm, hyperparameters, evaluation metrics, artifact URI, and timestamps

#### Scenario: Training fails

- **GIVEN** a training run encounters invalid data, missing artifacts, or runtime failure
- **WHEN** the worker records failure
- **THEN** the training run status becomes `FAILED`
- **AND** the failure reason is stored without exposing internal credentials or stack traces through public APIs

### Requirement: Model registry manages model versions and deployment state

The system SHALL store immutable model versions and mutable deployment status in
a model registry.

#### Scenario: Successful training registers a candidate model

- **GIVEN** a training run succeeds
- **WHEN** the model artifact and metrics are stored
- **THEN** the registry creates a model version with status `CANDIDATE`
- **AND** records dataset version, feature schema hash, artifact hash, compatible device types, metrics, and evaluation metrics

#### Scenario: Operator promotes a model

- **GIVEN** a candidate model passes review
- **WHEN** an authorized operator approves and activates it for a scope
- **THEN** the model status and deployment policy are updated
- **AND** promotion is audit logged
- **AND** previous active models remain available for rollback

### Requirement: Deployment policy resolves the active model safely

The system SHALL resolve model policy in device override, device type policy,
account default, system default, and baseline fallback order.

#### Scenario: Device override exists

- **GIVEN** a device has an active compatible model override
- **WHEN** analytics requests prediction for that device
- **THEN** the override model is selected
- **AND** the prediction result records the policy source as `DEVICE`

#### Scenario: No compatible active trained model exists

- **GIVEN** no active model is compatible with the device type or metric schema
- **WHEN** analytics requests prediction
- **THEN** the system uses deterministic rules and baseline heuristic fallback
- **AND** the prediction result records the fallback reason

### Requirement: Shadow and canary deployments are supported

The system SHALL allow trained models to run without immediately replacing the
current production prediction path.

#### Scenario: Model is deployed in shadow mode

- **GIVEN** a model policy is set to `SHADOW`
- **WHEN** analytics recalculates a device
- **THEN** the model prediction is stored as a shadow prediction
- **AND** risk scoring continues to use the current active path
- **AND** operators can compare shadow output with active output

### Requirement: Retraining can use internal and external data

The system SHALL allow training runs to reference dataset versions composed of
internal telemetry, external imported rows, or both.

#### Scenario: Operator retrains with mixed data

- **GIVEN** a dataset version contains internal telemetry and validated external imported rows
- **WHEN** the operator starts retraining
- **THEN** the training run records both source lineages
- **AND** evaluation metrics are computed against the configured validation/test split
- **AND** the resulting model version can be compared with the currently active model

