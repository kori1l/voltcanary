## ADDED Requirements

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
