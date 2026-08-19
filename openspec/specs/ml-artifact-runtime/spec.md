# ml-artifact-runtime Specification

## Purpose
Define the production ML artifact runtime boundary for training, storing,
verifying, registering, and loading durable model bundles by `modelVersionId`.
## Requirements
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

### Requirement: Model versions support safe lifecycle actions

Model versions SHALL support safe lifecycle actions that preserve training and
prediction traceability.

#### Scenario: Operator archives an approved inactive model

- **GIVEN** an approved model is not selected by any active deployment policy
- **WHEN** an authorized operator archives the model with a reason
- **THEN** the model SHALL be hidden from default deployable model selectors
- **AND** its artifact URI, manifest, hash, metrics, and lineage SHALL remain
  available for audit.

#### Scenario: Operator attempts to delete a policy-referenced model

- **GIVEN** a model version is referenced by an active deployment policy
- **WHEN** an operator requests model deletion
- **THEN** the system SHALL block deletion
- **AND** return the referencing policy ids and scopes.

#### Scenario: Operator deletes an unreferenced candidate model

- **GIVEN** a candidate model is not referenced by any policy or prediction
- **WHEN** an authorized operator deletes it with a reason
- **THEN** the model SHALL be removed from default registry views
- **AND** the deletion SHALL be audit logged.

### Requirement: Training runs support cleanup actions

Training runs SHALL support cleanup actions appropriate to their state.

#### Scenario: Operator deletes a failed training run

- **GIVEN** a training run failed before registering a model
- **WHEN** an authorized operator deletes the run with a reason
- **THEN** the run SHALL be hidden from default training-run lists
- **AND** failure audit data SHALL remain available in audit or archived views.

#### Scenario: Operator attempts to delete a successful training run with a model

- **GIVEN** a successful training run registered a model version
- **WHEN** an operator requests deletion
- **THEN** the system SHALL block hard deletion
- **AND** offer archive semantics that preserve model lineage.

### Requirement: ML-service execution endpoints enforce internal authorization

ML-service SHALL require an authenticated internal-service boundary for
production execution and metadata endpoints that can train models, load model
artifacts, or expose model metadata.

#### Scenario: Unauthorized training request is rejected

- **GIVEN** ML-service internal authorization is enabled
- **WHEN** a request calls `/train` without a valid internal service token
- **THEN** the service SHALL return an authorization error
- **AND** no training, artifact read, artifact write, or model registration
  work SHALL run.

#### Scenario: Authorized analytics request is accepted

- **GIVEN** analytics-service calls ML-service with the configured internal
  token and source-service header
- **WHEN** it requests `/inference` or `/train`
- **THEN** ML-service SHALL accept the request subject to normal validation
- **AND** logs or metrics SHALL attribute the request to the source service.

#### Scenario: Health probes remain unauthenticated

- **GIVEN** Kubernetes probes call ML-service
- **WHEN** they request `/health` or `/health/artifacts`
- **THEN** ML-service SHALL return probe status without requiring the internal
  service token.

### Requirement: ML-service execution requests are size-limited

ML-service SHALL reject oversized execution requests before they can consume
unbounded training or inference resources.

#### Scenario: Training payload exceeds configured limit

- **GIVEN** a `/train` request body exceeds the configured maximum request size
- **WHEN** ML-service receives the request
- **THEN** the service SHALL return `413` or an equivalent structured oversized
  request error
- **AND** no feature parsing, model fitting, or artifact persistence SHALL run.

### Requirement: Production ML artifacts use durable object storage

Production-like ML-service deployments SHALL use durable object storage for
model and feature artifacts, and SHALL NOT use the source tree as the runtime
artifact registry.

#### Scenario: Production starts with local artifact storage

- **GIVEN** ML-service is running in a production-like profile
- **AND** artifact storage is configured as `local`
- **WHEN** readiness checks run
- **THEN** `/health/artifacts` SHALL report an unhealthy artifact provider
- **AND** the pod SHALL NOT accept trained-model traffic.

#### Scenario: Production artifact bucket is unavailable

- **GIVEN** ML-service is configured with S3 artifact storage
- **BUT** the configured bucket or credentials are unavailable
- **WHEN** `/health/artifacts` is called
- **THEN** the response SHALL identify artifact storage as unavailable without
  exposing secrets
- **AND** readiness SHALL fail.

### Requirement: Generated artifacts are not committed as registry state

Generated model and feature artifacts SHALL be excluded from normal source
control and treated as runtime outputs or test fixtures only when explicitly
named.

#### Scenario: Training creates a model locally

- **GIVEN** a local or test training run creates a model bundle
- **WHEN** the repository status is inspected
- **THEN** generated model files SHALL NOT appear as normal source changes
  under the production artifact registry path.

### Requirement: Artifact cleanup preserves lineage references

Artifact cleanup SHALL preserve artifacts referenced by training runs, model
versions, deployment policies, prediction traceability, or audit records.

#### Scenario: Operator previews artifact cleanup

- **GIVEN** model artifacts exist in the configured artifact store
- **WHEN** an operator requests cleanup impact
- **THEN** the system SHALL identify unreferenced cleanup candidates
- **AND** block active or historically referenced artifacts from deletion.

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

### Requirement: Trained-model inference uses a verified serving cache

ML-service SHALL reuse previously verified trained-model runtime artifacts for
serving when the selected model metadata has not changed.

#### Scenario: First request loads and verifies a model

- **GIVEN** analytics-service selects a trained model with `modelVersionId`,
  `manifestUri`, and `artifactHash`
- **WHEN** ML-service serves the first compatible inference request for that
  model
- **THEN** it SHALL load the artifact bundle from the artifact store
- **AND** verify the manifest hash and file hashes before scoring
- **AND** place the loaded runtime artifact in the serving cache.

#### Scenario: Subsequent request reuses cached runtime

- **GIVEN** a verified cache entry exists for the selected model version,
  manifest URI, and artifact hash
- **WHEN** another compatible inference request selects the same metadata
- **THEN** ML-service SHALL score using the cached runtime artifact
- **AND** SHALL NOT reread or deserialize the same bundle from artifact storage
  for that request.

#### Scenario: Model metadata changes

- **GIVEN** a cache entry exists for a model version
- **WHEN** analytics-service selects the same model version with a different
  manifest URI or artifact hash
- **THEN** ML-service SHALL treat the cache entry as stale for that request
- **AND** reload and verify the new artifact metadata before scoring.

#### Scenario: Artifact load fails

- **GIVEN** a selected model bundle cannot be loaded or verified
- **WHEN** ML-service attempts to populate the serving cache
- **THEN** it SHALL NOT cache the failed artifact
- **AND** it SHALL return an explicit model-unavailable response.

