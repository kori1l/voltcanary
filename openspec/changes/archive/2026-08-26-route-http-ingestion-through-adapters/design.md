# Design: Route HTTP Ingestion Through Adapters

## Flow

Target HTTP flow:

```text
IngestionController
  -> IngressOrchestrator
  -> HttpTelemetryAdapter
  -> IncomingTelemetryMessage
  -> Common Ingestion Pipeline
```

The controller owns transport concerns: route, request DTO, auth header
extraction, client IP extraction, and HTTP response mapping. The HTTP adapter
owns DTO-to-canonical mapping for single, batch, and import modes.

## Batch And Import Semantics

Batch requests must keep per-message invariants. Shared optimizations such as
one source lookup or Kafka batching are allowed only if they preserve the same
validation, idempotency, timestamp, and event creation behavior as single
ingestion.

Import requests should set canonical import mode rather than use a separate
semantic stack.

## Compatibility Policy

Because the project is pre-production, implementation can replace existing
controller/service wiring directly. Keep endpoint shapes only when that keeps
the refactor focused; do not add compatibility shims around obsolete internals.

## Testing

Cover HTTP single, batch partial success, import timestamp policy, duplicate
handling, rate limiting, and Kafka publication through the adapter route.
