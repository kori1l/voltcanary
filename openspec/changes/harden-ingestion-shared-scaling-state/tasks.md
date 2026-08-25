# Tasks: Harden Ingestion Shared Scaling State

## 1. Shared Correctness State

- [ ] 1.1 Require Redis-backed idempotency for multi-replica ingestion mode.
- [ ] 1.2 Require Redis-backed rate limiting for multi-replica ingestion mode.
- [ ] 1.3 Make any in-memory idempotency or rate-limit behavior explicit local-only configuration.
- [ ] 1.4 Add health/metrics labels that distinguish shared correctness from local-only degraded behavior.

## 2. Failure Policy

- [ ] 2.1 Define Redis outage behavior for idempotency and rate limiting.
- [ ] 2.2 Define Kafka publish/backpressure behavior when broker acknowledgement fails.
- [ ] 2.3 Confirm `telemetry.raw.v1` uses a deterministic key and document the chosen key.

## 3. Documentation And Verification

- [ ] 3.1 Update ingestion API/events/operations docs for scaling and dependency failure modes.
- [ ] 3.2 Add tests for cross-replica idempotency and rate limiting behavior.
- [ ] 3.3 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [ ] 3.4 Run `npm run openspec:validate`.
