# Ingestion Protocol Adapters

## ADDED Requirements

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
