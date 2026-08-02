## ADDED Requirements

### Requirement: Training produces durable model artifacts

Successful training runs SHALL create durable model artifact bundles that can be
loaded later for inference.

#### Scenario: Training succeeds

- **GIVEN** a training run references a valid feature snapshot
- **WHEN** the ML worker completes training
- **THEN** the system SHALL store a model artifact bundle
- **AND** store artifact URI, hash, and manifest metadata
- **AND** verify the saved artifact can be loaded before registering a candidate model.

### Requirement: Model runtime identity is `modelVersionId`

ML-service SHALL load trained models by the model version id selected by
analytics-service deployment policy.

#### Scenario: Analytics selects an active model

- **GIVEN** analytics-service resolves a deployment policy to a model version id
- **WHEN** it requests inference from ML-service
- **THEN** ML-service SHALL resolve the same model version id to a loadable artifact
- **AND** the prediction response SHALL include that model version id.

#### Scenario: Selected model artifact is missing

- **GIVEN** a deployment policy selects a model version
- **BUT** the artifact cannot be loaded or hash verification fails
- **WHEN** inference is requested
- **THEN** ML-service SHALL return an explicit model-unavailable or model-incompatible status
- **AND** analytics-service SHALL use fallback when fallback is enabled.

### Requirement: Training runs reflect real worker execution

Training run status transitions SHALL correspond to actual worker execution and
shall not report success before artifacts and metrics are produced.

#### Scenario: Worker fails during artifact persistence

- **GIVEN** model fitting succeeds
- **BUT** artifact persistence fails
- **WHEN** the worker records completion
- **THEN** the training run SHALL be marked `FAILED`
- **AND** no candidate model SHALL be registered.

### Requirement: Training jobs are lease-controlled and cancelable

Workers SHALL coordinate training execution with leases, heartbeats, and
cooperative cancellation.

#### Scenario: Operator cancels a running job

- **GIVEN** a training run is running
- **WHEN** an authorized operator requests cancellation
- **THEN** the worker SHALL observe the cancellation request
- **AND** stop before registering a candidate model
- **AND** store status `CANCELED` with completion metadata.
