# Design: Align Ingestion Target Architecture

## Inbound Dispatch

Add a small inbound dispatch layer that resolves `InboundTelemetryAdapter`
instances from `IngestionAdapterRegistry` by adapter type, protocol, transport,
and capability. HTTP and MQTT transport components should construct typed
adapter requests, then ask the dispatcher to map them to
`IncomingTelemetryMessage` and pass them to `TelemetryIngestionPipeline`.

The dispatcher is not a new business pipeline. It only removes concrete adapter
selection from orchestration so future webhook, vendor push, or protocol gateway
ingress can be added by registering an adapter.

## Control-Plane Source Protocols

Extend the source protocol model in `device-service` to represent the active
connector adapters that already exist in ingestion:

- `MODBUS_TCP` -> `adapterType=GENERIC_MODBUS_TCP`,
  `protocol=MODBUS_TCP`, `transport=TCP`
- `OPC_UA` -> `adapterType=GENERIC_OPC_UA`, `protocol=OPC_UA`,
  `transport=TCP`

The source runtime config event must carry adapter configuration, mapping,
credential references, home region, and a monotonic config version. Raw secrets
must remain out of `source.runtime-config.v1`.

If the current data-source schema cannot yet persist mapping or adapter config,
add the minimal structured fields or typed value objects needed for the event
contract. Avoid embedding protocol details in ingestion's common pipeline.

## Lease Fencing

Connector ownership should expose a fencing token or monotonically increasing
lease generation in addition to owner identity. Polling and subscription
telemetry may continue to use current owner checks, but command-capable adapters
must not be added until fenced ownership can be passed to the command execution
boundary.

Redis can remain the first implementation. The important behavior is that a
renewed or newly acquired lease has observable fencing metadata, release only
affects the current owner/generation, and tests prove stale owners cannot act as
current owners after failover.

## Documentation

Update the target architecture and operational docs so they describe the
implemented baseline rather than a partially aspirational sequence. Keep the
future service split and multi-region sections as future evolution, but make the
current one-service baseline explicit.

## Testing

Use focused backend tests first:

- adapter registry/dispatcher tests for inbound HTTP and MQTT;
- source runtime config publisher tests for Modbus TCP and OPC UA;
- connector lease manager tests for fencing and stale-owner rejection;
- architecture structure tests proving the pipeline still has no concrete
  protocol dependencies.

Then run:

```powershell
cd backend
mvn -pl common/common-events,services/device-service,services/ingestion-service -am test
npm run openspec:validate
```
