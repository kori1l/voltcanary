# Design: Harden Ingestion Shared Scaling State

## Correctness State

In multi-replica mode, rate limiting and idempotency must use shared state within
the region. Redis is the current target. In-memory implementations may remain
for local development only if configuration names and health indicators make the
weaker behavior explicit.

## Dependency Failure Policy

Define explicit behavior:

- Redis unavailable: fail closed or enter a named degraded mode; never report
  strong cross-replica idempotency or rate limiting while using local memory.
- Kafka unavailable: apply bounded buffering/backpressure and reject or pause
  ingress when full.
- Source config lagging: continue serving known sources from local read model;
  newly changed sources may be temporarily unavailable.

## Kafka Key

`telemetry.raw.v1` should continue to use `deviceId` as the default Kafka key
unless a separate versioning change selects another key. Do not use random keys.

## Testing

Add tests for Redis outage behavior, duplicate detection across two simulated
instances, rate limits across replicas, deterministic Kafka key choice, and
backpressure when Kafka publishing fails.
