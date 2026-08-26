# Design: Verify Ingestion Horizontal Scale

## Verification Shape

Run at least two ingestion-service replicas with shared Kafka/Redpanda, Redis,
and source runtime config. The smoke should exercise:

- HTTP push can hit either replica;
- MQTT or equivalent push path still converges to canonical telemetry;
- source runtime config is available locally after restart;
- duplicate telemetry across replicas is rejected once;
- rate limit counters are shared;
- one active connector source has exactly one owner;
- owner failure transfers the source;
- Kafka records use the documented deterministic key.

## Environment

Prefer Docker Compose or the existing local Kubernetes path, depending on what
the repo already supports most reliably. The smoke must be scriptable and should
produce a concise summary artifact that is not committed unless explicitly
requested.

## Failure Policy

The gate fails if a source is polled by more than one replica, if idempotency or
rate limits silently fall back to local state, if source config requires hot-path
device-service calls, or if Kafka keys are random.

## Documentation

Document ports, profiles, required services, commands, expected health states,
and troubleshooting for Redis, Kafka, source config lag, and connector leases.
