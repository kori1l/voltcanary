# Ingestion Architecture

## ADDED Requirements

### Requirement: Inbound Ingestion Uses Registry Dispatch

Inbound transport components SHALL resolve inbound telemetry adapters through a
registry-backed dispatcher rather than binding orchestration to concrete adapter
implementations.

#### Scenario: HTTP push telemetry is accepted

- **WHEN** `POST /ingestion/readings` receives a supported realtime reading
- **THEN** the HTTP transport path SHALL select an inbound adapter through the
  adapter registry or dispatcher
- **AND** the selected adapter SHALL emit canonical telemetry before the common
  ingestion pipeline runs.

#### Scenario: MQTT telemetry is accepted

- **WHEN** the MQTT transport receives a supported telemetry message
- **THEN** MQTT topic and payload parsing SHALL remain inside the MQTT adapter
  package
- **AND** transport orchestration SHALL not depend on MQTT adapter internals
  beyond the inbound adapter contract.

#### Scenario: Another inbound adapter is added

- **WHEN** a webhook, vendor push, or future inbound protocol adapter is added
- **THEN** it SHALL be registered with manifest metadata and inbound capability
- **AND** `DefaultTelemetryIngestionPipeline` SHALL NOT require changes to
  select or parse that adapter's protocol payload.
