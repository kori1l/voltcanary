# Ingestion Source Runtime Config

## ADDED Requirements

### Requirement: Control Plane Publishes Active Connector Runtime Config

The control-plane source model SHALL represent every active connector adapter
that ingestion can run, including HTTP polling, Modbus TCP, and OPC UA.

#### Scenario: Modbus TCP source is configured

- **WHEN** an operator creates or updates a Modbus TCP source
- **THEN** `device-service` SHALL publish `source.runtime-config.v1` with
  `adapterType=GENERIC_MODBUS_TCP`, `protocol=MODBUS_TCP`, and `transport=TCP`
- **AND** the event SHALL include versioned adapter configuration, register
  mapping, credential references, home region, status, and monotonically
  comparable config version.

#### Scenario: OPC UA source is configured

- **WHEN** an operator creates or updates an OPC UA source
- **THEN** `device-service` SHALL publish `source.runtime-config.v1` with
  `adapterType=GENERIC_OPC_UA`, `protocol=OPC_UA`, and `transport=TCP`
- **AND** the event SHALL include versioned adapter configuration, node mapping,
  credential or certificate references, home region, status, and monotonically
  comparable config version.

#### Scenario: Runtime config contains credential references

- **WHEN** Modbus TCP, OPC UA, HTTP polling, or vendor source config is
  published
- **THEN** raw protocol credentials SHALL NOT be present in mapping,
  adapterConfig, or credentialReferences
- **AND** ingestion adapters SHALL resolve credentials through the configured
  credential provider.
