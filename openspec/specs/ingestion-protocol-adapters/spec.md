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

