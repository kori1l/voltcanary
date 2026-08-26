# Tasks: Build Ingestion Connector Runtime

## 1. Runtime Core

- [x] 1.1 Add connector runtime, source assignment, lease manager, scheduler, supervisor, and health model.
- [x] 1.2 Add connector state transitions for starting, running, degraded, stopped, failed, and lease-lost states.
- [x] 1.3 Ensure source runtime config updates start, stop, or restart connectors deterministically.
- [x] 1.4 Add retry/backoff policy with jitter for protocol operation and lifecycle failures.

## 2. Ownership

- [x] 2.1 Implement source lease acquisition, renewal, expiry, and release.
- [x] 2.2 Stop local connector work immediately after lease loss.
- [x] 2.3 Prove a source is not polled or subscribed by every replica.

## 3. Documentation And Verification

- [x] 3.1 Document connector runtime states, ownership, health, and failure behavior.
- [x] 3.2 Add unit/integration tests for assignment, leases, failover, retry, and config updates.
- [x] 3.3 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [x] 3.4 Run `npm run openspec:validate`.
