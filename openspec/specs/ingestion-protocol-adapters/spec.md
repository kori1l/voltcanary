# ingestion-protocol-adapters Specification

## Purpose
Define protocol adapter behavior for HTTP polling, Modbus TCP, and OPC UA so
active connector telemetry enters VoltCanary through the same canonical
ingestion pipeline as push telemetry.
## Requirements
### Requirement: HTTP Polling Adapter Uses Connector Runtime Ownership

Generic HTTP polling SHALL be implemented as a polling adapter invoked by the
connector runtime for lease-owned sources.

#### Scenario: HTTP polling source is due

- **GIVEN** the connector runtime owns an active HTTP polling source lease
- **WHEN** the source polling interval is due
- **THEN** the runtime SHALL invoke the HTTP polling adapter
- **AND** the adapter SHALL map the HTTP response into canonical telemetry input
  for the common pipeline.

#### Scenario: HTTP polling source is not owned locally

- **WHEN** an ingestion instance does not own the source lease
- **THEN** it SHALL NOT poll the source
- **AND** it SHALL NOT publish telemetry for that source's polling interval.

### Requirement: HTTP Polling Config Is Versioned And Secret Safe

HTTP polling adapter configuration SHALL be versioned and SHALL reference
credentials without embedding raw secrets in source runtime config.

#### Scenario: Adapter config is loaded

- **WHEN** a source uses the generic HTTP polling adapter
- **THEN** ingestion SHALL validate the adapter config schema version, endpoint,
  timeout, interval, timestamp mapping, metric mapping, and credential
  references
- **AND** raw credentials SHALL be resolved through the credential provider.

### Requirement: Modbus TCP Adapter Does Not Change Common Pipeline Semantics

Modbus TCP SHALL be implemented as a polling adapter that maps registers into
canonical telemetry before common ingestion processing.

#### Scenario: Modbus polling succeeds

- **GIVEN** the connector runtime owns an active Modbus TCP source lease
- **WHEN** the Modbus adapter reads configured registers successfully
- **THEN** it SHALL map register values into canonical metric names and values
- **AND** the common ingestion pipeline SHALL process the result without knowing
  Modbus register addresses, function codes, or unit IDs.

#### Scenario: Modbus mapping is invalid

- **WHEN** source runtime config contains an invalid Modbus register mapping
- **THEN** the adapter or config validator SHALL reject the source config and
  report per-source health
- **AND** the common ingestion pipeline SHALL NOT receive partially interpreted
  protocol objects.

### Requirement: Modbus Polling Uses Connector Runtime Ownership

Modbus polling SHALL run only for sources owned by the local connector runtime.

#### Scenario: Multiple ingestion replicas are running

- **WHEN** a Modbus TCP source is active
- **THEN** exactly one replica SHALL hold the source lease and poll it
- **AND** other replicas SHALL not create duplicate Modbus connections for the
  same source.

### Requirement: OPC UA Adapter Uses Subscription Lifecycle Ownership

OPC UA SHALL be implemented as a subscription-capable adapter whose sessions are
started and stopped by the connector runtime for lease-owned sources.

#### Scenario: OPC UA subscription starts

- **GIVEN** the connector runtime owns an active OPC UA source lease
- **WHEN** source configuration is valid
- **THEN** the runtime SHALL start the OPC UA adapter subscription for configured
  nodes
- **AND** node changes SHALL be mapped into canonical telemetry input before
  common pipeline processing.

#### Scenario: Lease is lost while session is active

- **WHEN** the local runtime loses the source lease
- **THEN** the OPC UA adapter SHALL close the local session and subscriptions
- **AND** it SHALL stop emitting telemetry for that source from this replica.

### Requirement: OPC UA Details Stay Outside The Common Pipeline

OPC UA node addressing, session state, and security mechanics SHALL remain in
the OPC UA adapter and connector runtime layers.

#### Scenario: Node value changes

- **WHEN** an OPC UA node emits a value change
- **THEN** the adapter SHALL convert it to canonical metric values and bounded
  origin/context metadata
- **AND** the common ingestion pipeline SHALL NOT depend on NodeId, namespace,
  browse path, session, or security policy classes.
