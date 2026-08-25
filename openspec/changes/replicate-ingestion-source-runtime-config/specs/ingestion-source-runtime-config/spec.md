# Ingestion Source Runtime Config

## ADDED Requirements

### Requirement: Source Runtime Config Is Replicated To The Data Plane

`ingestion-service` instances SHALL maintain a local source runtime
configuration read model fed by a versioned compacted event topic.

#### Scenario: Source configuration changes

- **WHEN** a source is created, updated, disabled, moved between regions, or
  deleted
- **THEN** the control-plane owner SHALL publish a `source.runtime-config.v1`
  change with a deterministic source key and monotonically comparable version
- **AND** each ingestion instance SHALL update its local source runtime read
  model from that event stream.

#### Scenario: Ingestion validates telemetry

- **WHEN** telemetry reaches ingestion after the local read model is populated
- **THEN** ingestion SHALL resolve source status, account, device, adapter type,
  protocol, transport, mapping, and credential references locally
- **AND** it SHALL NOT synchronously call `device-service` for every telemetry
  event.

### Requirement: Runtime Config Does Not Contain Raw Secrets

Source runtime configuration SHALL reference credentials without embedding raw
protocol credentials in general config events or caches.

#### Scenario: Adapter needs credentials

- **WHEN** an adapter needs MQTT, OPC UA, vendor API, HTTP polling, or other
  protocol credentials
- **THEN** it SHALL resolve them through a credential provider using references
  from source runtime config
- **AND** raw secrets SHALL NOT be published on `source.runtime-config.v1`.

### Requirement: Regional Ownership Is Represented In Source Runtime Config

Source runtime configuration SHALL carry enough regional metadata for a future
control-plane/data-plane split.

#### Scenario: Regional data plane processes source config

- **WHEN** an ingestion instance receives source runtime config for a source
- **THEN** it SHALL know the source home region or intended data-plane region
- **AND** future regional deployments SHALL NOT require global synchronous
  source lookups in the telemetry hot path.
