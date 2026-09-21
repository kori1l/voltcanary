## 1. Profiles and fixtures

- [x] 1.1 Implement validated versioned profiles and exact workload arithmetic; distinguish current-5 and resume-10.
- [x] 1.2 Record the metric-contract decision; implement and test ten legitimate numeric definitions across affected surfaces if selected, otherwise retain BLOCKED and document a revised claim separately.
- [x] 1.3 Implement resumable API provisioning for cohort/accounts/devices/sources/tokens, per-replica readiness and scoped cleanup.
- [x] 1.4 Capture sanitized deployment, resource, rate-limit, catalog, partition, retention and downstream-policy manifests.

## 2. Generation and orchestration

- [x] 2.1 Implement pinned k6 REST scenarios with deterministic scheduling, multiple-worker partitioning, batch item accounting and same-identity retries.
- [x] 2.2 Implement pinned MQTT generator with explicit QoS/connections, reconnect accounting and application persistence reconciliation.
- [x] 2.3 Implement smoke/baseline/hourly/daily/burst/stress/mixed profiles, configurable fixed thresholds and exact expected totals excluding warmup/drain.
- [x] 2.4 Implement runner phases, bounded time/disk/error/lag guards, cancellation report and fault restoration.

## 3. Evidence and observability

- [x] 3.1 Inventory/reuse existing meters; add missing histogram, consumer-lag and database/node observations with documented scrape queries.
- [x] 3.2 Implement sampled committed-visibility observation, polling uncertainty and timeout accounting.
- [x] 3.3 Implement bounded full logical-key/value reconciliation, retry/unknown-outcome resolution and deliberate corruption detection tests.
- [x] 3.4 Implement sanitized JSON and readable reports with per-claim PASS/FAIL/BLOCKED/INCONCLUSIVE/NOT_RUN verdicts and generator validity checks.

## 4. Storage, scaling and recovery

- [ ] 4.1 Implement resumable import and explicitly storage-only SQL fixtures, pilot disk measurements, checkpoint validation and 30-day data distribution.
- [ ] 4.2 Implement monthly-volume query/write trial with real index/chunk/retention inventory and cold/warm phases.
- [ ] 4.3 Implement comparable fixed-replica runs and separate HPA timeline capture; account for partitions and total resources.
- [ ] 4.4 Implement bounded consumer/Redis/Kafka/database recovery trials and final reconciliation; distinguish restart from replicated failover.

## 5. Verification and documentation

- [ ] 5.1 Test invalid profiles, missing metrics, dropped schedules, timeout-after-accept, duplicate retries, batch partial success, observer timeout, checkpoint resume and cleanup boundaries.
- [ ] 5.2 Run a short isolated current-5 integration smoke and retain sanitized evidence; run affected module tests for instrumentation or metric changes.
- [ ] 5.3 Run the user-selected one-hour local profile after readiness fixes; report measured hourly totals and explicitly extrapolated daily volume. Daily endurance and full monthly-volume trials are optional extensions and remain NOT_RUN unless separately selected.
- [ ] 5.4 Verify fixed-replica scaling/recovery and MQTT profiles with actual evidence before making those claims.
- [x] 5.5 Align API/event docs if contracts change, promote runnable guidance to current operations, and update skills/indices to actual commands.
- [ ] 5.6 Run openspec:validate and structure:check; archive only after required implementation and verification tasks are complete.

## Review follow-up 2026-09-18

See [review.md](review.md) for reproduced blockers R1-R8. Reopened boxes retain
credit for partial work but require real implementations and verification, not
only JSON plans or in-memory sample tests. The hourly profile and arithmetic
checks are present; no live capacity run has been performed.
