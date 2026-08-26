# ingestion-protocol-adapters Specification

## Purpose
TBD - created by archiving change add-http-polling-ingestion-adapter. Update Purpose after archive.
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

