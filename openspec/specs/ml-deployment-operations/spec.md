# ml-deployment-operations Specification

## Purpose
TBD - created by archiving change ml-deployment-runtime-monitoring. Update Purpose after archive.
## Requirements
### Requirement: Live policy resolution uses device type

Analytics-service SHALL include device type when resolving live ML deployment
policy.

#### Scenario: Device-type policy exists

- **GIVEN** a device has no device-specific policy
- **AND** its device type has an active policy
- **WHEN** analytics recalculates predictions
- **THEN** the device-type policy SHALL be selected
- **AND** prediction traceability SHALL record policy source `DEVICE_TYPE`.

### Requirement: Canary deployment is deterministic

Canary deployment SHALL apply a deterministic inclusion rule using account,
device, model version, and configured canary percentage.

#### Scenario: Device is outside canary cohort

- **GIVEN** a model policy is `CANARY` with a configured percentage
- **AND** the deterministic hash excludes the device
- **WHEN** analytics recalculates predictions
- **THEN** the candidate model SHALL not affect active prediction
- **AND** traceability SHALL record that the device was not canary-included.

### Requirement: Shadow predictions remain separate from active output

Shadow deployments SHALL store candidate predictions without changing active
risk, recommendations, or latest non-shadow prediction output.

#### Scenario: Shadow model scores high risk

- **GIVEN** a shadow model returns high risk
- **WHEN** analytics recalculates predictions
- **THEN** the high-risk result SHALL be stored as a shadow prediction
- **AND** user-facing active risk SHALL continue to use the active or fallback path.

### Requirement: Production registry does not seed demo models

Demo model entries SHALL not appear in production model registries unless an
explicit demo/local profile is enabled.

#### Scenario: Production account has no trained models

- **GIVEN** the system runs with production registry settings
- **WHEN** an account lists ML models
- **THEN** the response SHALL include only real trained models and explicit system heuristics
- **AND** no demo candidate or approved model SHALL be auto-created.

### Requirement: ML operations are observable

The system SHALL expose model deployment and inference monitoring metrics usable
by operators and data engineers.

#### Scenario: Model fallback increases

- **GIVEN** an active model begins falling back due to feature mismatch or unavailable artifacts
- **WHEN** monitoring metrics are scraped
- **THEN** fallback rate, feature mismatch count, and model-specific error metrics SHALL expose the degraded state.

### Requirement: Deployment policies are safely manageable

Operators SHALL be able to disable or delete ML deployment policies from the
console without corrupting runtime policy resolution.

#### Scenario: Operator disables an active device policy

- **GIVEN** a device-scoped policy is active
- **WHEN** an authorized operator disables the policy with a reason
- **THEN** the policy SHALL no longer be selected for live inference
- **AND** policy resolution SHALL fall back to the next applicable policy or
  baseline behavior
- **AND** the action SHALL be audit logged.

#### Scenario: Operator deletes an inactive policy

- **GIVEN** a deployment policy is inactive or disabled
- **WHEN** an authorized operator deletes it
- **THEN** the policy SHALL be removed from default policy lists
- **AND** historical prediction traceability SHALL remain readable.

#### Scenario: Operator attempts unsafe policy deletion

- **GIVEN** deleting a policy would remove the only active serving path without
  fallback
- **WHEN** the operator requests deletion
- **THEN** the system SHALL require explicit confirmation or block the action
- **AND** return a structured explanation of affected scope.

### Requirement: ML deployment verification covers incident outcomes

Deployment verification SHALL prove that active, shadow, canary, and fallback
policies produce the expected prediction and alert/incident behavior.

#### Scenario: Active high-risk deployment creates user-visible incident

- **GIVEN** an active deployment policy selects a compatible trained model
- **AND** that model produces high-risk output
- **WHEN** analytics and alert processing complete
- **THEN** latest prediction SHALL identify the active model and policy source
- **AND** alert-service SHALL create a user-visible `OPEN` alert unless alert
  suppression is configured.

#### Scenario: Shadow high-risk deployment does not create active incident

- **GIVEN** a shadow deployment produces high-risk output
- **WHEN** active prediction remains low-risk
- **THEN** the shadow result SHALL be recorded separately
- **AND** no user-visible active incident SHALL be created from the shadow
  result alone.

#### Scenario: Canary exclusion does not create model incident

- **GIVEN** a canary deployment excludes the device deterministically
- **WHEN** the candidate model would have produced high risk
- **THEN** the active prediction and incident outcome SHALL follow the current
  active or fallback path
- **AND** traceability SHALL record canary exclusion.

#### Scenario: Fallback path creates or suppresses incidents consistently

- **GIVEN** an active model is unavailable and fallback is enabled
- **WHEN** fallback produces high risk
- **THEN** alert behavior SHALL follow the fallback prediction trace
- **AND** the alert source SHALL not claim that the unavailable trained model
  produced the incident.

