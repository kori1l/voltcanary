# ingestion-architecture Specification

## Purpose
Define the permanent adapter boundary for `ingestion-service` so every
protocol-specific ingress path emits canonical telemetry before the common
ingestion pipeline performs validation, idempotency, rate limiting, and Kafka
publication.
## Requirements
### Requirement: Ingestion Uses A Permanent Adapter Boundary

`ingestion-service` SHALL treat every protocol-specific entry point as an
adapter that produces canonical telemetry input before the common ingestion
pipeline runs.

#### Scenario: A new protocol is introduced

- **WHEN** a new protocol or vendor integration is added
- **THEN** the implementation SHALL add or register a protocol adapter
- **AND** the common ingestion pipeline SHALL NOT parse that protocol's topic,
  frame, register, node, resource path, or vendor payload shape.

#### Scenario: Existing ingestion paths are refactored

- **WHEN** HTTP, MQTT, or import ingestion is changed
- **THEN** transport-specific code SHALL map input through an adapter boundary
- **AND** downstream validation, idempotency, rate limiting, and Kafka
  publication SHALL operate on the canonical ingress model.

### Requirement: Canonical Telemetry Input Is Protocol Neutral

The canonical ingress model SHALL carry VoltCanary telemetry semantics and
bounded origin/context metadata without embedding concrete protocol models.

#### Scenario: Adapter emits telemetry

- **WHEN** an adapter accepts a protocol-specific payload
- **THEN** it SHALL emit a canonical telemetry message containing source,
  device, account when known, occurred time, received time, metrics, origin,
  context, and import mode
- **AND** fields such as MQTT topic, Modbus register, OPC UA NodeId, and vendor
  response body SHALL NOT be required by the common pipeline.

### Requirement: Adapter Contracts Are Capability Specific

Adapter APIs SHALL model inbound, polling, subscription, command, and discovery
capabilities as separate contracts or explicit capability flags.

#### Scenario: Polling adapter is implemented

- **WHEN** an adapter needs active polling behavior
- **THEN** it SHALL implement the polling capability contract
- **AND** it SHALL NOT receive unrelated subscription or command methods as part
  of a monolithic adapter interface.

### Requirement: Ingestion Dependency Direction Is Enforced

The common ingestion core SHALL depend only on canonical models, adapter
contracts, and explicit ports, not on concrete HTTP, MQTT, industrial, or vendor
adapter implementations.

#### Scenario: Architecture verification runs

- **WHEN** ingestion architecture tests or structure checks run
- **THEN** they SHALL fail if common pipeline packages depend on concrete
  protocol adapter packages
- **AND** they SHALL fail if telemetry-service depends on ingestion adapter
  packages.

### Requirement: HTTP Ingestion Routes Through The Adapter Boundary

HTTP single, batch, and import requests SHALL be translated to canonical
telemetry input through the HTTP adapter before common ingestion processing.

#### Scenario: Single HTTP reading is accepted

- **WHEN** `POST /ingestion/readings` receives a valid realtime reading
- **THEN** the controller SHALL delegate to ingress orchestration
- **AND** the HTTP adapter SHALL produce canonical telemetry input for the
  common pipeline.

#### Scenario: Batch HTTP reading is accepted

- **WHEN** `POST /ingestion/readings/batch` receives multiple readings
- **THEN** each reading SHALL pass through the same canonical validation,
  idempotency, rate-limit, and event creation invariants as single ingestion
- **AND** batch-level optimizations SHALL NOT bypass per-message correctness.

#### Scenario: Historical import is accepted

- **WHEN** an import endpoint accepts historical telemetry
- **THEN** the HTTP adapter SHALL set canonical import mode
- **AND** the common pipeline SHALL apply the import timestamp policy without a
  separate telemetry semantics stack.

