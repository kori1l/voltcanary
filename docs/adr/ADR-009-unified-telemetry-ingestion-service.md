# ADR-009: Unified Telemetry Ingestion Service

## Status

Accepted

## Context

VoltCanary supports multiple telemetry input protocols: HTTP, MQTT, CSV/import,
HTTP polling, Modbus TCP, OPC UA, and future vendor integrations. Creating
separate services for each protocol would duplicate source resolving,
validation, idempotency, connector health, lease ownership, and canonical event
publishing logic.

## Decision

Use a single telemetry-ingestion-service bounded context. HTTP push/import,
MQTT, HTTP polling, Modbus TCP, OPC UA, CSV/import, and future vendor protocols
are implemented as adapters inside this service. All adapters produce
canonical `IncomingTelemetryMessage` values and call the same
`TelemetryIngestionPipeline`, which publishes canonical
`TelemetryReceivedEvent` to `telemetry.raw.v1`.

The adapter boundary is explicit in code through adapter manifests,
capabilities, adapter/protocol/transport types, source runtime configuration,
registry, resolver, and capability-specific inbound, polling, and subscription
contracts. The common ingestion core depends only on canonical models and
adapter API contracts, not concrete HTTP, MQTT, Modbus, OPC UA, or vendor
adapter packages.

## Consequences

Benefits:
- one source resolving mechanism
- one idempotency mechanism
- one canonical telemetry event publishing path
- no duplicated ingestion logic
- easier testing and documentation
- stable adapter contract for future protocol and connector additions

Trade-offs:
- service becomes larger
- HTTP and MQTT have different scaling profiles
- production may deploy the same service image in different adapter modes
- adapter API changes must be kept compatible with the common pipeline and
  source runtime configuration model
