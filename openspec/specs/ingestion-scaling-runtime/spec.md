# ingestion-scaling-runtime Specification

## Purpose
Define the shared correctness state, connector ownership, dependency failure,
and verification requirements that make ingestion safe to run across multiple
replicas.
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

### Requirement: Horizontal Scale Verification Covers Push And Active Sources

VoltCanary SHALL provide repeatable verification that ingestion can run with
multiple replicas while preserving push-ingress and active-connector correctness.

#### Scenario: Multi-replica smoke runs

- **WHEN** the horizontal ingestion smoke starts at least two ingestion-service
  replicas with shared Kafka and Redis
- **THEN** push telemetry SHALL be accepted through any healthy replica
- **AND** duplicate detection, rate limiting, source config reads, connector
  ownership, and Kafka keying SHALL be verified across replicas.

#### Scenario: Active connector owner fails

- **WHEN** the replica that owns an active connector source stops or loses its
  lease
- **THEN** another eligible replica SHALL acquire the source after the expected
  failover interval
- **AND** the source SHALL NOT be polled or subscribed by two replicas at the
  same time.

### Requirement: Horizontal Deployment Behavior Is Documented

Horizontal ingestion operation SHALL be documented with required shared
dependencies, profiles, health expectations, and troubleshooting steps.

#### Scenario: Operator runs ingestion horizontally

- **WHEN** an operator or agent follows the horizontal ingestion deployment docs
- **THEN** the required Kafka, Redis, source runtime config, connector lease,
  and health-check configuration SHALL be clear
- **AND** known failure modes SHALL identify whether ingestion should reject,
  degrade, retry, or continue from local read model state.

### Requirement: Connector Ownership Is Fenced For Future Commands

Connector runtime ownership SHALL expose fencing metadata that can distinguish
current ownership from stale ownership after failover.

#### Scenario: Active connector ownership changes

- **WHEN** another ingestion instance acquires a source after lease expiry,
  release, or failover
- **THEN** the new ownership SHALL have a fencing token or generation different
  from the stale owner
- **AND** renew, release, polling publication, and subscription forwarding
  checks SHALL reject stale ownership.

#### Scenario: Command-capable adapter is introduced

- **WHEN** a future adapter adds command execution capability
- **THEN** command execution SHALL require current fenced connector ownership
  for stateful source connections
- **AND** owner identity alone SHALL NOT be sufficient authorization to execute
  a command through a stateful connector.
