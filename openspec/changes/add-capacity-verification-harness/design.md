# Capacity Verification Design

## Workload identity

Use a versioned profile with devices, tenants, sources, numeric metric definitions,
interval, phase distribution, protocol/QoS/connections, batch size, retries,
duration, random seed, read mix, downstream policy and thresholds.
The `resume-10` profile requires 10,000 devices at 300-second intervals and ten
supported numeric values per reading. The distinct `current-5` profile preserves
the interval with five values and a different expected total. Profile validation
must happen before a destructive or expensive preparation step.

The ten-metric contract is `activePowerW` (`W`), `voltageV` (`V`), `currentA`
(`A`), `energyWh` (`Wh`), `rssi` (`dBm`), `temperatureC` (`C`),
`vibrationMmS` (`mm/s`), `runtimeHours` (`h`), `powerFactor` (`ratio`) and
`frequencyHz` (`Hz`). These names are implemented consistently in ingestion
validation, telemetry/query metadata, frontend metric metadata, ML unit
normalization and load-test profiles. Unsupported profiles still block before
destructive or expensive work.

## Components and ownership

- `scripts/load-tests/`: versioned profiles; PowerShell entrypoints; k6 REST
  scenarios; a pinned MQTT client generator; manifest/reconciliation/report tools.
- Fixture preparation uses public identity/device/source APIs and readiness
  barriers for each ingestion runtime-config replica. Keep provisioning outside
  measurement and resume through durable local progress records.
- External generator maintains a deterministic device/time schedule. For multiple
  workers, assign disjoint sequence ranges; retries never advance logical identity.
  Credentials are local secret inputs, not serialized in evidence reports.
- Observer uses read-only committed queries or supported query APIs to detect
  sampled readings independently of the producer. A proposed 1% sample and 250 ms
  polling interval are configurable and included in evidence.
- Reconciler regenerates expected logical keys/values from manifest plus outcomes,
  then compares sorted/chunked key sets against committed measurements. Partition
  reconciliation by cohort and time to bound memory and database work.
- Runner implements prepare/preflight/run/reconcile/report/cleanup, cancellation,
  guards and bounded drain. It restores only targets it faulted. Cleanup is scoped
  to its fixture manifest and is independently invocable.

## Accounting and timing

Track scheduled readings, started readings, transport attempts, retries, accepted
unique readings, duplicates, rejects, unknown outcomes and persisted numeric values.
Inspect batch items and HTTP response bodies. MQTT transport acknowledgements
are not application acceptance. Unknown timeouts require same-identity retries
or reconciliation and cannot silently count as failure or successful delivery.

The measurement key is `(device_id, source_id, recorded_at, metric_name)`;
reading timestamps and accepted event IDs are not interchangeable. Full comparison
must detect missing/unexpected keys and wrong values, not only matching counts.
For baseline valid realtime data require zero unexplained losses after drain;
intentional invalid/rate-limit cases have a separate expected-outcome ledger.

In-process counters may increment before commit, and processing timers end before
transaction completion. Use committed visibility for persistence evidence;
polling observations are upper bounds with interval/network error disclosed.
Keep timeout samples as censored failures rather than dropping them. Per-endpoint
and per-phase quantiles require histogram buckets or raw samples. No averaging
of replica percentiles; no device/source/run IDs as unbounded metric labels.

## Instrumentation and storage

Inventory actual scrape output before adding instrumentation. Reuse
`voltcanary.ingestion.*`, `voltcanary.telemetry.*` and `telemetry.outbox.*`.
Add histogram configuration, missing group-lag collection and database/node
resource collection only where absent. Record actual series names and queries.

Storage fixture loader checkpoints time/cohort windows and validates schema,
counts and checksums. Pilot 1M/10M values to measure table/index/raw/inbox/outbox/WAL
cost and estimate required headroom. A full 864M test remains separate evidence.
Import mode exercises import semantics; direct SQL mode is storage-only and must
preserve selected schema/index/auxiliary-table distribution and document omissions.
Verify deployed hypertables/chunks, retention and compression instead of assuming
these from the database product name. Keep an ongoing live read/write workload
on the fully prepared dataset, with cold and warm query phases recorded separately.

## Controlled comparisons and failures

Separate one/two/three-replica tests from HPA tests. Fix resources per replica,
shared dependencies, dataset, seed and limits; report total resources and actual
partitions. An improvement is measured sustainable throughput within the same
thresholds, not replica count. Record HPA scale-out/readiness/assignment and drain.

Use one fault at a time, with a bounded outage and finally-based restoration.
Accepted data must reconcile after Redis/Kafka/consumer/database recovery.
Single-node restart evidence must not be labeled stateful HA, backup restore or
RPO=0. Those claims need a dedicated replicated topology and separate trials.

## Validation and rollout

User scope update: prefer an hourly local run (3,600 measured seconds plus
300-second warmup and bounded 300-second drain). Project observed throughput to
daily volume with explicit assumptions; never mark daily endurance or populated
monthly storage PASS from that projection. Those trials are optional extensions.
See [implementation review](review.md) for blockers found before actual execution.

First validate malformed profiles and accounting on tiny deterministic fixtures.
Exercise dropped schedules, ambiguous acceptance, duplicate retries, partial batch
success and forced missing/unexpected rows to prove reports fail correctly.
Run a short isolated current-5 smoke before longer tests; run resume-10 only
after the short smoke and environment readiness gate pass. Keep daily and
monthly trials explicit, outside routine CI. Publish run environment and
claim-specific verdicts in ignored artifacts. Move the target procedure into
current operations when executable.

## Open decisions

- Dedicated host resources, available disk, deployment and runtime budget.
- REST/MQTT mix, MQTT connection topology and desired stateful HA claim scope.
- Final latency/recovery thresholds; initial proposals are in the test procedure.

These decisions block their dependent runs, not preparation of the harness.
