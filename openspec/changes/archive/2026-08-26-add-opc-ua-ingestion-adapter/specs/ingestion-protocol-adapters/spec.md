# Ingestion Protocol Adapters

## ADDED Requirements

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
