# ingestion-scaling-runtime Specification

## Purpose
TBD - created by archiving change harden-ingestion-shared-scaling-state. Update Purpose after archive.
## Requirements
### Requirement: Multi-Replica Ingestion Uses Shared Correctness State

When ingestion runs in a multi-replica mode, idempotency and rate limiting SHALL
use shared state within the data-plane region.

#### Scenario: Duplicate telemetry reaches different replicas

- **GIVEN** ingestion is configured for multi-replica operation
- **WHEN** the same telemetry event reaches two different ingestion replicas
- **THEN** shared idempotency state SHALL allow only one canonical event to be
  accepted
- **AND** the service SHALL NOT silently fall back to in-memory duplicate
  detection while still reporting multi-replica correctness.

#### Scenario: Rate limit applies across replicas

- **GIVEN** ingestion is configured for multi-replica operation
- **WHEN** requests for the same source, device, account, or client identity hit
  multiple replicas
- **THEN** rate limits SHALL be counted against shared regional state
- **AND** local counters SHALL NOT be treated as authoritative.

### Requirement: Dependency Failure Behavior Is Explicit

Ingestion SHALL define observable behavior for Redis, Kafka, and source runtime
configuration failures.

#### Scenario: Shared correctness store is unavailable

- **WHEN** Redis or the configured shared correctness store is unavailable
- **THEN** ingestion SHALL fail closed or enter an explicitly named degraded mode
- **AND** health and metrics SHALL NOT imply strong cross-replica correctness.

#### Scenario: Kafka publish is unavailable

- **WHEN** `telemetry.raw.v1` cannot be published within configured bounds
- **THEN** ingestion SHALL apply bounded backpressure and reject or pause ingress
  when capacity is exhausted
- **AND** it SHALL NOT accept unlimited telemetry into memory.

### Requirement: Kafka Partitioning Is Deterministic

Canonical telemetry events SHALL be published with a documented stable Kafka key.

#### Scenario: Telemetry raw event is published

- **WHEN** ingestion publishes to `telemetry.raw.v1`
- **THEN** the Kafka record key SHALL be deterministic, with `deviceId` as the
  default key unless a separate versioned contract changes it
- **AND** random keys SHALL NOT be used for canonical telemetry.

### Requirement: Active Connectors Are Owned By The Connector Runtime

Stateful active connectors SHALL be assigned and leased by a protocol-neutral
connector runtime rather than by individual adapters.

#### Scenario: Active source is eligible to run

- **WHEN** source runtime config marks a polling or subscription source active
- **THEN** the connector runtime SHALL acquire ownership before starting adapter
  work
- **AND** exactly one runtime owner SHALL run that source at a time.

#### Scenario: Runtime owner loses lease

- **WHEN** an ingestion instance loses or fails to renew a source lease
- **THEN** it SHALL stop the local connector instance for that source
- **AND** another eligible instance MAY acquire the source after lease expiry or
  transfer.

### Requirement: Connector Scheduling Is Centralized

Polling schedules SHALL be coordinated by the connector runtime and SHALL NOT be
created independently by every polling adapter.

#### Scenario: Polling source is scheduled

- **WHEN** a source has a polling interval in runtime config
- **THEN** the connector scheduler SHALL invoke the selected polling adapter at
  the configured cadence
- **AND** the adapter SHALL NOT create an unrelated always-on scheduler for the
  same source.

### Requirement: Connector Health Is Per Source

Connector runtime health SHALL isolate individual source failures from the whole
ingestion service.

#### Scenario: One connector source fails repeatedly

- **WHEN** one source experiences protocol timeouts, invalid config, credential
  failure, or endpoint unavailability
- **THEN** that source SHALL report degraded or failed health with retry state
- **AND** other sources and stateless ingress paths SHALL continue when their
  dependencies are healthy.

