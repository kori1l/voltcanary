## ADDED Requirements

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
