# Local capacity harness review 2026-09-18

Archive note (2026-10-05): the user requested archival with four tasks open.
The live-rerun requirement below remains unmet; historical references to
"before archival" describe the normal evidence gate, not a passing result.
See proposal.md for the explicit incomplete-snapshot disposition.

Verdict: not ready for a credible live capacity run. This is a source review,
read-only host inspection and offline validation, not a load-test result.
The existing PowerShell harness unit suite passes; it does not execute the k6
script against ingestion or implement the live observation pipeline.

## Follow-up status 2026-09-29

This document preserves the findings from the 2026-09-18 review. The current
implementation resolves the fixture/source mapping, metric-value parity,
unique k6 iteration identity, fixed run epoch, complete-schedule requirement,
duplicate detection, MQTT connection reuse and resumable token persistence.

The final readiness gaps were addressed in the follow-up implementation:

- hourly/baseline warmup is a distinct k6 scenario with separate timestamps and
  is excluded from measured totals;
- k6 observes sampled committed visibility from the accepted response until the
  telemetry export first returns the exact timestamp/value, and evaluates p95;
- the runner continuously checks artifact and Docker-data disks, k6 aborts on
  sustained HTTP failure rate, and Prometheus error/consumer-lag guards are polled;
- local Compose runs export committed TimescaleDB measurements automatically,
  wait through bounded drain and reconcile in time windows to bound memory.

Live smoke and hourly evidence now exist. The `current-5` smoke passed. The first
one-hour `resume-10` run completed but failed generation validity, which exposed
that readiness sampled only ten sources. A second hour after full-ID readiness
reduced rejects from 3,755 to 48, but four sources retained stale token configs.
The barrier now also compares every expected token prefix. That final correction
still needs a live full-cohort rerun before archival. Daily endurance and the
864M-value monthly population remain optional and `NOT_RUN`. Scaling, recovery
and MQTT claims also remain separate until selected.

## Selected local scope

User selected one measured hour on their own computer instead of 24 hours.
`hourly` describes 10,000 devices, one reading every 300 seconds, 120,000
readings and 1.2M values for resume-10, plus five minutes warmup and up to five
minutes drain. The runner executes the complete REST schedule with `-Execute`,
samples committed visibility and performs automatic TimescaleDB reconciliation.

Daily projection = observed reconciled unique count / measured seconds * 86,400.
At complete target delivery this is 2.88M readings and 28.8M values per day.
It does not prove 24-hour endurance, storage/query capacity at 864M values, or HA.
Those long/full-volume experiments remain optional and unmeasured locally.

## Live evidence 2026-09-29

The isolated smoke `local-smoke-20260929-032744` passed: 1,000/1,000 readings
were accepted, all 5,000 numeric values reconciled, and all ten visibility
samples were observed with p95 262 ms.

The measured-hour run `local-hourly-resume10-20260929-034408` scheduled and
started all 120,000 readings over 3,600 seconds with zero dropped iterations.
It accepted and persisted 116,245 readings / 1,162,450 numeric values, rejected
3,755 readings and retried six requests. Full streaming reconciliation passed
with zero missing, unexpected, duplicate, non-finite or wrong values among the
accepted set. Visibility was `INCONCLUSIVE`: 97/100 samples were observed,
three timed out, p95 was 264 ms and p99 was 270 ms.

The measured persisted rate was 32.290278 readings/s and 322.902778 numeric
values/s. Its arithmetic projection is 2,789,880 readings and 27,898,800 values
per day. This is an extrapolation from the failed hourly run, not a daily
endurance or monthly-storage result. The overall hourly verdict is `FAIL` because
3,755 application rejections violate the zero-rejection generation gate.

The rerun `local-hourly-resume10-rerun-20260929-174105` started all 120,000
readings, accepted and persisted 119,952 readings / 1,199,520 values, rejected
48 and dropped none. Visibility passed with 100/100 samples, p95 265 ms and p99
269 ms. Reconciliation found zero missing, unexpected, duplicate, non-finite or
wrong values in the accepted set. The measured persisted rate was 33.32
readings/s and 333.2 values/s; its daily extrapolation is 2,878,848 readings and
28,788,480 values. The strict verdict remains `FAIL`.

All 48 rejects came from four device indexes (1009, 2840, 6378 and 6688), once
per cycle. Direct diagnostic requests confirmed `Invalid source token` even
though the device API and local secret file agreed on the newest token. Rotating
only those four tokens repaired all four immediately. Readiness therefore now
checks token-prefix parity in addition to source/config presence.

The rerun also exposed quadratic postprocessing in generation-result assembly.
Hash-set accounting reduced the same 120,000-outcome import to about 56 seconds;
streaming reconciliation of 1,199,520 values completed with bounded memory.

In the first hourly run, rejections concentrated in the first two cycles while
provisioning events were still reaching the ingestion runtime, with three sources
remaining unavailable. The old ten-source sample therefore allowed traffic to
start before the whole fixture was ready. Provisioning now performs bounded
parallel API work and posts all provisioned data-source IDs to the opt-in bulk
readiness endpoint on every ingestion replica. A timeout makes provisioning
`BLOCKED`.

Restarting the local ingestion container also showed that its runtime source
registry is rebuilt only from newly consumed configuration events; an existing
consumer group did not repopulate old sources. Recovery remains `NOT_RUN`, and a
restart between provisioning and measurement invalidates readiness until source
configuration is republished. This is a separate recovery limitation, not proof
of stateful failover.

## Host observations

- AMD Ryzen 7 4800H, 8 cores / 16 logical processors.
- Physical RAM about 31.4 GiB; about 11.5 GiB available at inspection.
- Docker Desktop WSL2 reports 15.33 GiB and 16 CPUs available to its VM.
- C: about 36.8 GiB free; D: about 309 GiB free.
- Docker data was moved to `D:\DockerData`; the measured run retained about
  293 GiB free on D: and did not put its database growth on C:.
- Docker statistics show Kubernetes control-plane workloads. A full VoltCanary
  application stack was not observed in the statistics sample.
- Native k6 was not found on PATH. PowerShell 7 is available; the system Windows
  PowerShell fails OpenSpec apply at New-TemporaryFile. Direct invocation from
  PowerShell 7 succeeds.

Assessment: the local computer sustained the generator and selected Compose
services without resource exhaustion. During the hour k6 used about 1.4 GiB,
ingestion about 459 MiB, telemetry about 411 MiB and TimescaleDB peaked near
684 MiB. This establishes host feasibility for another one-hour run, while the
failed generation verdict still prevents a capacity claim. Do not prefill 864M
rows on this local scope.

## Blocking findings

### R1 P1 Resolve real fixture identities and source-scoped batches

`New-CapacityFixtureSchedule` generates synthetic device/source UUIDs;
provisioning saves returned `apiDeviceId`, `apiDataSourceId` and `externalId`
in provisioning-state, but `New-CapacityGenerationPlan` still copies the synthetic
schedule. k6 indexes tokens by apiDataSourceId and looks up the synthetic sourceId.
Even with a manual SOURCE_TOKEN, SourceResolver requires sourceId=externalId
and the actual device UUID. Additionally, default batches combine 100 different
sources under the first source's single bearer token. The real contract rejects
other sources. Map fixture indices to provisioned identities, index tokens by
externalId and use one source per request (one reading per request for this cadence).
Validate a real two-source smoke before claiming readiness.

### R2 P1 Fix REST numeric values, iteration identity and timestamps

In rest-ingest.k6.js, only the original five metrics have generators. For new
metrics the fallback emits sequence % 1000: sequence 2 produces powerFactor=2
(maximum 1), and sequence 200 produces temperatureC=200 (maximum 150).
The PowerShell expected-value generator uses different formulas, so even accepted
values cannot reconcile. These examples were reproduced offline with Node.

`batches[__ITER]` repeats the same batches across VUs because __ITER is per-VU;
use an explicit unique scenario/worker sequence. See
[k6 execution context](https://grafana.com/docs/k6/latest/using-k6/execution-context-variables/).
`Date.now() + recordedAtOffsetSeconds` is recomputed at send time when RUN_START
is absent, advancing timestamps twice relative to the schedule and eventually
producing future readings. Persist one common run epoch and share it with observer,
retry and reconciler. Test emitted payloads, multiple VUs and the actual API.

### R3 P1 Execute the entire schedule at the declared profile

The default materialized schedule contains at most 10,000 readings. Generation
uses only that prefix and derives rate from its length over the full duration.
For hourly this is only one of twelve device cycles; generation validity compares
against the materialized prefix, not required 120,000 readings. Multiple workers
also lack an executed aggregation path. Generate lazily from the complete schedule
or stream chunks, require full worker coverage and reject incomplete plans.

Warmup/readMix currently exist as metadata, not executed phases. Stress increases
the count but timestamps retain the original interval; burst still uses a uniform
arrival executor. Implement actual phase schedules or label unsupported phases
BLOCKED. Do not fix only the displayed arithmetic.

### R4 P1 Wire live observation, threshold evaluation and real guards

run.ps1 always passes -DryRun. Its runner refuses real generation and writes plans.
The k6 script has no export/adapter to the harness generation-result.json contract;
optional console outcomes are not automatically consumed. Visibility accepts
already-supplied samples, reconciliation accepts in-memory rows; there is no live
database/API observation and bounded acquisition path in these entrypoints.

Only dropped_iterations is a k6 threshold. Offline reproduction:
New-CapacityVisibilityObservation with observed=true, latencyMillis=60000 returns
PASS despite the configured five-second target. HTTP p95/p99 are likewise not
evaluated by the report. Wire measured samples and thresholds into claim verdicts.

Runner guards evaluate supplied error/lag values once before dry-run; they do not
monitor/stop the separately started generator. Disk checking measures RunDirectory
on D:, missing Docker data growth on C:. Add ongoing host/VM disk, error, lag and
time supervision, cancellation, bounded drain and real restoration before live runs.

### R5 P1 Preserve duplicate evidence in reconciliation

Test-CapacityLogicalReconciliation assigns committed rows into a dictionary by
key, overwriting duplicates. Passing one expected measurement and the same
committed row twice returns PASS with committedMeasurements=1. Reproduced offline.
Record duplicate counts before insertion, check expected input uniqueness and
reject non-finite values; test the actual bounded database export path.

### R6 P1 MQTT does not implement the requested load model

mqtt-generator.ps1 launches a synchronous mosquitto_pub process per message with
no arrival wait or persistent parallel connection pool. ConnectionCount only
changes client IDs; it does not create that many simultaneous connections.
Timestamps use now+offset, and provisioning hardcodes protocol REST. Generation
still uses synthetic source IDs. Create MQTT sources and map real external IDs;
implement paced persistent clients and observable concurrency or block this mode.
The proposed eclipse-mosquitto image command also invokes powershell, which is
not supplied by that image. Pin and check the actual supported runtime.

### R7 P2 Provisioning cannot reliably resume token issuance

After each token rotation, provisioning-state is checkpointed but secrets are
written only after the whole device loop. A failure halfway leaves rotations
marked complete without the raw tokens; a retry skips issuing them. Persist
tokens durably before marking completion, and detect/recover missing secrets.
Also separate dry-run state from real state so PLANNED records cannot be mistaken
for created resources on a later real invocation.

### R8 P2 Several completed tasks only produce plans

Storage fixture/monthly/scaling/recovery entrypoints write JSON plan objects.
There is no corresponding data loader, query execution, scale operation or
fault injection in them. Existing instrumentation is useful but config inventory
is not evidence of live scrapes. Reopened tasks distinguish implementation from
descriptions, and skill text no longer claims only five metrics exist in source.

## Fix and verification order

1. R1/R2: actual two-source REST integration and shared payload-value tests.
2. R3/R4/R5: full schedule, measured outcomes, observer/reconciler, live guards
   and failing regression cases; prove an intentionally invalid run cannot PASS.
3. Short local smoke, measure RAM and disk growth, then hourly full-cohort REST.
4. Implement R6/R7 for MQTT/provisioning reliability; re-run affected boundary tests.
5. Keep monthly/HPA/fault execution optional until specifically selected; do not
   close their implementation tasks merely because planning commands succeed.

The blocking findings above record the original 2026-09-18 review. Their status
is superseded by the 2026-09-29 follow-up at the top of this document. The strict
hourly claim remains failed until the token-aware readiness implementation passes
a fresh live run.
