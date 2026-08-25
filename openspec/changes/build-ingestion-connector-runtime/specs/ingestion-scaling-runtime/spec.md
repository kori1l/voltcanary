# Ingestion Scaling Runtime

## ADDED Requirements

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
