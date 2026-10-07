# Archived delivery snapshot

Archived on 2026-10-05 at the user's explicit request with 19/23 tasks complete.
The four unchecked tasks remain outstanding. This closure overrides the normal
completion-before-archive workflow for this snapshot only; it does not satisfy
the live verification gate in 5.7. See proposal.md for the archive disposition.
The incomplete spec delta was not applied to main specs.

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

## 4. Optional extensions

- [x] 4.1 Keep 24-hour endurance and the 864M-value population outside the selected local scope; retain explicit `NOT_RUN` verdicts and non-claiming storage plans.
- [x] 4.2 Preserve optional monthly query/write, schema/index/retention and pilot-estimation contracts without executing or requiring the full dataset.
- [ ] 4.3 Implement comparable fixed-replica runs and separate HPA timeline capture; account for partitions and total resources.
- [ ] 4.4 Implement bounded consumer/Redis/Kafka/database recovery trials and final reconciliation; distinguish restart from replicated failover.

## 5. Verification and documentation

- [x] 5.1 Test invalid profiles, missing metrics, dropped schedules, timeout-after-accept, duplicate retries, batch partial success, visibility threshold/timeout, warmup separation, bounded reconciliation and cleanup boundaries.
- [x] 5.2 Run a short isolated current-5 integration smoke and retain sanitized evidence; run affected module tests for instrumentation or metric changes.
- [x] 5.3 Run the user-selected one-hour local profile and report its claim-level result, measured hourly totals and explicitly extrapolated daily volume. The first run failed generation validity; a second run reduced rejects from 3,755 to 48 and exposed four stale token configs. A passing rerun after token-aware readiness remains separate verification work. Daily endurance and full monthly-volume trials remain NOT_RUN unless separately selected.
- [ ] 5.4 Verify fixed-replica scaling/recovery and MQTT profiles with actual evidence before making those claims.
- [x] 5.5 Align API/event docs if contracts change, promote runnable guidance to current operations, and update skills/indices to actual commands.
- [x] 5.6 Run openspec:validate and structure:check.
- [ ] 5.7 Archive only after token-aware full-cohort readiness has passed a live rerun and the required tasks are complete. Optional daily/monthly/scaling/recovery/MQTT claims may remain `NOT_RUN`.

## Review follow-up 2026-09-18

See [review.md](review.md) for the original reproduced blockers R1-R8 and the
2026-09-29 live evidence. Optional boxes remain open because plans are not live
scaling, recovery or MQTT evidence.
