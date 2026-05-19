# ADR-009: Unified Telemetry Ingestion Service

## Status

Accepted

## Context

VoltCanary supports multiple telemetry input protocols: HTTP, MQTT, CSV and future vendor integrations. Creating separate services for HTTP ingestion and MQTT connector would duplicate source resolving, validation, idempotency and canonical event publishing logic.

## Decision

Use a single telemetry-ingestion-service bounded context. HTTP, MQTT, CSV and future vendor protocols are implemented as input adapters inside this service. All adapters call the same TelemetryIngestionPipeline and publish canonical TelemetryReceivedEvent to telemetry.raw.v1.

## Consequences

Benefits:
- one source resolving mechanism
- one idempotency mechanism
- one canonical telemetry event publishing path
- no duplicated ingestion logic
- easier testing and documentation

Trade-offs:
- service becomes larger
- HTTP and MQTT have different scaling profiles
- later we may deploy the same service image in different adapter modes
