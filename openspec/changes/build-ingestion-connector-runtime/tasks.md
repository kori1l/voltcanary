# Tasks: Build Ingestion Connector Runtime

## 1. Runtime Core

- [ ] 1.1 Add connector runtime, source assignment, lease manager, scheduler, supervisor, and health model.
- [ ] 1.2 Add connector state transitions for starting, running, degraded, stopped, failed, and lease-lost states.
- [ ] 1.3 Ensure source runtime config updates start, stop, or restart connectors deterministically.
- [ ] 1.4 Add retry/backoff policy with jitter for protocol operation and lifecycle failures.

## 2. Ownership

- [ ] 2.1 Implement source lease acquisition, renewal, expiry, and release.
- [ ] 2.2 Stop local connector work immediately after lease loss.
- [ ] 2.3 Prove a source is not polled or subscribed by every replica.

## 3. Documentation And Verification

- [ ] 3.1 Document connector runtime states, ownership, health, and failure behavior.
- [ ] 3.2 Add unit/integration tests for assignment, leases, failover, retry, and config updates.
- [ ] 3.3 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [ ] 3.4 Run `npm run openspec:validate`.
