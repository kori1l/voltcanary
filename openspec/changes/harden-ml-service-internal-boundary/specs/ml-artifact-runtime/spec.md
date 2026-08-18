## ADDED Requirements

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
