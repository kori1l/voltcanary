# ml-artifact-runtime Specification

## ADDED Requirements

### Requirement: ML Runtime Components Are Focused

ML-service training, inference, and artifact storage behavior SHALL be split
into focused runtime components while preserving public API compatibility.

#### Scenario: Training behavior changes

- **WHEN** a change modifies idempotency, cancellation, model fitting,
  approval gates, model card metadata, or artifact publication
- **THEN** the affected behavior SHALL be implemented in a focused coordinator,
  runner, evaluator, or publisher
- **AND** route handlers SHALL continue to expose the existing training
  contract.

#### Scenario: Inference behavior changes

- **WHEN** a change modifies heuristic scoring, trained model scoring, feature
  error handling, or serving cache resolution
- **THEN** model resolution, scoring, and response assembly SHALL remain
  independently testable
- **AND** unavailable models SHALL still return explicit controlled statuses.

### Requirement: Artifact Providers Are Explicit

Local and S3 artifact storage SHALL be implemented as explicit providers behind
a common artifact-store boundary.

#### Scenario: Production artifact health is checked

- **WHEN** `/health/artifacts` checks a production-like runtime
- **THEN** provider health SHALL be evaluated by the configured provider
- **AND** local-storage rejection, S3 credential checks, bucket checks, and
  sanitized error responses SHALL remain covered by tests.

#### Scenario: Artifact cleanup is previewed

- **WHEN** cleanup impact is previewed or an artifact bundle is deleted
- **THEN** reference matching and deletion planning SHALL be separated from
  provider-specific list/delete I/O
- **AND** referenced artifacts SHALL remain protected.

### Requirement: ML Tests Do Not Mutate Global Settings Directly

ML-service tests SHALL isolate settings changes through fixtures or explicit
settings providers.

#### Scenario: Test overrides runtime settings

- **WHEN** a test needs a temporary model registry path, artifact provider,
  internal token, source allowlist, or request-size limit
- **THEN** it SHALL use a fixture-scoped settings instance or provider override
- **AND** the setting SHALL be restored automatically before another test runs.
