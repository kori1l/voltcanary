# Local capacity harness review 2026-09-18

Verdict: not ready for a credible live capacity run. This is a source review,
read-only host inspection and offline validation, not a load-test result.
The existing PowerShell harness unit suite passes; it does not execute the k6
script against ingestion or implement the live observation pipeline.

## Selected local scope

User selected one measured hour on their own computer instead of 24 hours.
`hourly` now describes 10,000 devices, one reading every 300 seconds, 120,000
readings and 1.2M values for resume-10, plus five minutes warmup and up to five
minutes drain. The current runner prepares plans only; adding this profile does
not fix the blockers below or start a test.

Daily projection = observed reconciled unique count / measured seconds * 86,400.
At complete target delivery this is 2.88M readings and 28.8M values per day.
It does not prove 24-hour endurance, storage/query capacity at 864M values, or HA.
Those long/full-volume experiments remain optional and unmeasured locally.

## Host observations

- AMD Ryzen 7 4800H, 8 cores / 16 logical processors.
- Physical RAM about 31.4 GiB; about 11.5 GiB available at inspection.
- Docker Desktop WSL2 reports 15.33 GiB and 16 CPUs available to its VM.
- C: about 36.8 GiB free; D: about 309 GiB free.
- Docker data VHDX is on C: under the user's LocalAppData Docker/wsl/data;
  its host file size is about 56.8 GiB. This is not the VM filesystem free space.
- Docker statistics show Kubernetes control-plane workloads. A full VoltCanary
  application stack was not observed in the statistics sample.
- Native k6 was not found on PATH. PowerShell 7 is available; the system Windows
  PowerShell fails OpenSpec apply at New-TemporaryFile. Direct invocation from
  PowerShell 7 succeeds.

Assessment: 33.33 readings/s is a plausible initial target for this machine,
not a measured capacity guarantee. Memory of the complete Java/ML/database stack,
write amplification and Docker's C: disk are the actual readiness gates. Start
with five minutes on 100 devices, then a short full-cohort baseline, and only
then one hour. Do not prefill 864M rows on this local scope. Before live execution
inspect free space inside Docker and C:, record actual stack memory and measure
pilot growth. The plan's default 20% disk headroom is not met on C:; either free
space/move Docker with user involvement or select a measured absolute reserve.

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

This review changes the hourly plan and task tracking only; the generator and
evidence defects above remain open. No live telemetry load or infrastructure
configuration mutation was performed.
