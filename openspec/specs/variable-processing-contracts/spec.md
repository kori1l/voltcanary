# variable-processing-contracts Specification

## Purpose
TBD - created by archiving change propagate-variable-processing-contracts. Update Purpose after archive.
## Requirements
### Requirement: Secret-free versioned configuration

Device-service SHALL publish source.processing-config.v1 through outbox keyed by dataSourceId with immutable generation/digest/content and without source credentials.

#### Scenario: Duplicate snapshot

- **WHEN** the same processing snapshot is delivered twice
- **THEN** projection state remains unchanged and no duplicate activation is created

#### Scenario: Conflicting generation

- **WHEN** a different digest arrives for an already known source generation
- **THEN** the conflict is rejected and diagnosed without replacing the immutable snapshot

### Requirement: Complete recoverable local projections

Ingestion replicas SHALL resolve compatible source and processing contracts locally; telemetry SHALL durably retain historical bundles and reconcile missing generations.

#### Scenario: Replica restart

- **WHEN** an ingestion replica restarts while a source is active
- **THEN** it rebuilds complete required source coverage before accepting configured input

#### Scenario: Old generation recovery

- **WHEN** a delayed event references a generation absent from the latest compacted record
- **THEN** telemetry can retrieve the exact retained historical bundle through authenticated reconciliation

### Requirement: Trusted acceptance context

Configured raw events SHALL carry server-pinned source UUID, bundle generation/digest and input schema version outside user metrics, with acceptance-time semantics.

#### Scenario: Edit during delivery

- **WHEN** revision two activates after an event was accepted under revision one
- **THEN** the event still references revision one's bundle

#### Scenario: Spoofed context

- **WHEN** a caller supplies reserved processing metadata or computed identifiers
- **THEN** the input cannot select a trusted bundle or inject computed output

### Requirement: Input contracts and dependency retention

Configured numeric inputs SHALL pass finite declared-input validation and retained-dependency checks before raw publication while legacy built-in checks remain intact.

#### Scenario: Temperature raw input

- **WHEN** a declared sensorTemperatureRaw input is 235 with scale applied later
- **THEN** it is accepted under its input contract without applying temperatureC output limits

#### Scenario: Missing propagation

- **WHEN** the source requires a processing generation not available locally
- **THEN** configured acceptance returns retryable unavailable rather than using latest or silently filtering required fields

### Requirement: Durable unknown-bundle recovery

Accepted events whose pinned bundle is unavailable SHALL be durably parked, reconciled with bounded retries and eventually DLQed without losing replay identity.

#### Scenario: Recovery

- **WHEN** the missing bundle arrives after parking
- **THEN** the original event is processed once using its pinned context

#### Scenario: Exhausted reconciliation

- **WHEN** the exact pinned bundle remains unavailable after the retry budget
- **THEN** the event enters a diagnosed DLQ and is not processed with another bundle

