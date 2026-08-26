# Design: Define Ingestion Adapter Contracts

## Target Boundary

The first slice creates a protocol-neutral contract before any protocol-specific
refactor. Keep one deployable `ingestion-service`, but make its internal shape
look like a gateway with clear package or module boundaries:

- application wiring and ingress orchestration;
- adapter API and registry;
- canonical ingestion core;
- concrete adapters;
- source runtime configuration model;
- Kafka output and observability.

Use Maven modules only if the local build can absorb the churn cleanly; strict
packages plus architecture tests are acceptable for this slice.

## Canonical Model

`IncomingTelemetryMessage` remains the handoff into the common pipeline, but it
must describe VoltCanary semantics rather than a transport payload. It should
carry source/device/account identity, occurred/received timestamps, metrics,
origin, ingestion context, and import flag. Protocol details such as MQTT topics,
Modbus registers, or OPC UA node IDs stay in adapter metadata only when needed
for observability or audit.

## Adapter API

Create small capability-specific contracts instead of one large interface:

- base adapter metadata and manifest;
- inbound telemetry handling;
- polling;
- subscription;
- command capability placeholder only when it clarifies future extension.

The common ingestion core depends on adapter contracts and canonical models, not
on concrete protocol packages.

## Testing

Add architecture tests for dependency direction and unit tests for registry,
manifest validation, and canonical message construction. Run the ingestion Maven
test slice plus OpenSpec validation.
