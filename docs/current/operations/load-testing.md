# Load Testing Operations

Status: current executable preflight, fixture schedule, API provisioning,
bounded REST/MQTT generation execution, outcome-accounting,
visibility-observation and logical reconciliation harness plus storage-volume
fixture, monthly query/write, scaling/HPA and recovery trial planning. Full
storage-volume execution and measured scaling/recovery runs remain tracked by
`openspec/changes/add-capacity-verification-harness`.

## Scope

### Local hourly mode

The user's selected scope is one measured hour on their own computer. The
`hourly` phase defines 3,600 measured seconds, 300 seconds warmup and up to
300 seconds drain: 120,000 readings / 1,200,000 numeric values for resume-10.
Daily and full monthly-volume runs are optional extensions, not required locally.
Project daily volume from reconciled observed totals divided by actual measured
seconds and multiplied by 86,400. Label it an estimate, not daily endurance or
populated monthly storage evidence.

The command below prepares the full hourly schedule and writes dry-run evidence
only. Add `-Execute` after fixture provisioning, source-token rotation,
ingestion readiness checks and environment capture. A completed hourly run still
requires generation-result, visibility-observation and reconciliation evidence
before any throughput claim can pass.

```powershell
pwsh -File scripts/load-tests/run.ps1 -Profile resume-10 -Phase hourly -RunnerPhase generate
```

Use PowerShell 7 (`pwsh`) for the harness. On the reviewed machine the system
`powershell.exe` lacks `New-TemporaryFile`, which breaks the OpenSpec apply wrapper.
The wrapper succeeds when invoked directly from PowerShell 7.

The current load-test tooling validates versioned workload profiles, exact
capacity arithmetic and unsupported-profile blockers before any expensive or
destructive operation. It writes sanitized evidence under ignored
`artifacts/load-tests/<run-id>/`.

The harness keeps `current-5` as the five-value comparison profile and uses
`resume-10` for the ten-value claim profile. A `current-5` preflight or future
run does not prove the `resume-10` claim.

Fixture provisioning uses the prepared `fixture-manifest.json` and calls the
device-service account, location, device, data-source and source-token APIs. It
records resumable progress in `provisioning-state.json`; provisioning and
cleanup are outside measured telemetry throughput.

Generation planning writes protocol-specific manifests for k6 REST and MQTT
entrypoints. After provisioning, REST generation resolves fixture devices to
API device IDs, resolves sources to their external IDs, keeps the API data-source
ID for source-token lookup and sends one reading per request so a request never
mixes multiple sources under one bearer token. A generation plan is not capacity
evidence by itself. Capacity claims require observed generator results,
committed-visibility samples and logical-key reconciliation against persisted
telemetry. `analyze.ps1` rewrites `summary.json` and `report.md` from the
available evidence files before it prints results, including
`overallEvidenceStatus` and generator-validity findings for dropped schedules,
ambiguous outcomes, mismatched protocols, unexpected sequences and rejected
readings.

## Commands

Run the current supported profile preflight:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/run.ps1 -Profile current-5 -Phase preflight
```

Validate the ten-metric resume profile before generation:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/run.ps1 -Profile resume-10 -Phase preflight
```

Unsupported profiles exit with code `2` and still write a sanitized summary
containing the blocker. Analyze a completed preflight directory with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/analyze.ps1 -RunDirectory artifacts/load-tests/<run-id>
```

Prepare a deterministic fixture and schedule manifest without sending traffic:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/prepare-schedule.ps1 -Profile current-5 -Phase smoke -WorkerCount 3
```

Prepare a smoke generation plan through the main runner. `MaxMaterializedReadings`
defaults to `0`, meaning the complete schedule; pass a positive number only for
bounded development subsets:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/run.ps1 -Profile current-5 -Phase smoke -Protocol rest
```

Execute the generated REST plan through the runner after fixture provisioning
and local source tokens are available. The runner uses local `k6` when present,
otherwise `docker run grafana/k6:0.52.0`; use `-K6Mode local` or
`-K6Mode docker` to force one mode. Keep `RUN_START` unset to use the fixed
epoch recorded in `generation-manifest.json`, or set it explicitly when
coordinating observer and reconciler timestamps:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/run.ps1 -Profile current-5 -Phase smoke -Protocol rest -RunnerPhase generate -Execute -K6Mode docker
```

The runner stores raw generator output in `generation-output.log`, imports the
application-level k6 outcome events into `generation-result.json`, and keeps the
k6 summary as `k6-summary.json` when k6 writes one. To import a separately run
k6 log into application-level generation evidence:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/import-k6-outcomes.ps1 -RunDirectory artifacts/load-tests/<run-id>
```

After generation, collect bounded committed-visibility samples through the
telemetry export API. Timeouts remain in `visibility-observations.json`; this
sample is latency/availability evidence and does not replace full logical
key/value reconciliation.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/observe-visibility.ps1 -RunDirectory artifacts/load-tests/<run-id> -TelemetryBaseUrl http://localhost:8084 -SamplePercent 1 -MaxSamples 100
```

Run full logical key/value reconciliation from a committed-measurements CSV.
The CSV must include `deviceId`, `sourceId`, `recordedAtOffsetSeconds` or
`recordedAt`, `metricName`, and `numericValue`. The reconciler expects accepted
sequences plus ambiguous `unknown` and `timeout-after-accept` identities; missing,
unexpected, duplicate, non-finite or wrong values fail integrity.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/reconcile-committed.ps1 -RunDirectory artifacts/load-tests/<run-id> -CommittedMeasurementsPath artifacts/load-tests/<run-id>/committed-measurements.csv
```

Prepare and dry-run the MQTT generator manifest:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/run.ps1 -Profile current-5 -Phase smoke -Protocol mqtt -MaxMaterializedReadings 1000
powershell -ExecutionPolicy Bypass -File scripts/load-tests/provision-fixtures.ps1 -RunDirectory artifacts/load-tests/<run-id> -SourceProtocol MQTT -RotateSourceTokens
powershell -ExecutionPolicy Bypass -File scripts/load-tests/mqtt-generator.ps1 -RunDirectory artifacts/load-tests/<run-id> -DryRun
```

The MQTT entrypoint uses the repository PowerShell MQTT 3.1.1 TCP publisher for
actual publishes, with explicit QoS 0/1, persistent connection slots and schedule
pacing from `generation-manifest.json`. Execute it through the runner with
`-Execute` or directly via `mqtt-generator.ps1`. MQTT fixtures must be provisioned with
`-SourceProtocol MQTT`; REST data sources are intentionally not reused for MQTT
claims. MQTT broker acknowledgement is recorded only as transport evidence;
application acceptance and persistence still require visibility observation and
logical-key reconciliation.

Plan fixture API provisioning without network calls:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/provision-fixtures.ps1 -RunDirectory artifacts/load-tests/<run-id> -LimitDevices 10 -DryRun
```

Provision a prepared smoke fixture against a local device-service and sample
ingestion readiness:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/provision-fixtures.ps1 -RunDirectory artifacts/load-tests/<run-id> -DeviceApiBaseUrl http://localhost:8082 -IngestionBaseUrls http://localhost:8083 -RotateSourceTokens
```

Clean up only the provisioned fixture scope:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/provision-fixtures.ps1 -RunDirectory artifacts/load-tests/<run-id> -Cleanup
```

Capture a sanitized environment manifest for a run or standalone evidence
directory:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/collect-environment.ps1 -RunId <run-id>
```

Plan the 30-day storage fixture without loading rows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/storage-fixture.ps1 -Profile current-5 -Mode storage-only-sql
```

The default `current-5` storage fixture represents 30 days of the supported
profile: 86,400,000 readings and 432,000,000 numeric values split into
resumable checkpoint windows. `storage-only-sql` is explicitly storage-only; it
does not prove ingestion, Kafka, outbox, analytics or alert throughput. Use
`-Mode import-api` when the fixture should exercise historical import semantics.

The `resume-10` storage fixture uses the implemented ten-metric contract:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/storage-fixture.ps1 -Profile resume-10 -Mode storage-only-sql
```

Plan the monthly-volume query/write trial after `storage-fixture.json` exists:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/monthly-trial.ps1 -RunDirectory artifacts/load-tests/<run-id>
```

This writes the cold/warm query phases, live-write window, required
hypertable/chunk/index/retention/size inventory and checkpoint integrity gates.
It remains `INCONCLUSIVE` until the full fixture has been loaded and verified.

Plan comparable fixed-replica and separate HPA evidence capture:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/scaling-trial.ps1 -RunDirectory artifacts/load-tests/<run-id> -ReplicaCounts 1,2,3
```

The plan records fixed-replica runs separately from HPA timeline capture and
requires recorded resources, shared dependency settings, Kafka partitions,
dataset, seed and limits before any scaling claim can pass.

Plan bounded recovery trials:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/recovery-trial.ps1 -RunDirectory artifacts/load-tests/<run-id> -FaultTargets consumer,redis,kafka,database
```

Each trial injects one target at a time and requires restoration plus final
reconciliation. A single-process restart result is scoped to restart recovery
and does not establish replicated HA, backup restore or RPO=0.

Run harness unit checks:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load-tests/test.ps1 -CiMode
```

## Evidence Files

Each preflight writes:

| File | Purpose |
| --- | --- |
| `manifest.json` | Sanitized profile, phase, seed, metric names and workload arithmetic. |
| `summary.json` | Status, blockers, warnings, claim verdicts, overall evidence status, generator validity and pending harness capabilities. |
| `report.md` | Human-readable report with claim-level verdicts, generator validity checks and any observed generation, visibility, reconciliation or cancellation evidence. |
| `fixture-manifest.json` | Deterministic account, device and source identifiers for schedule preparation runs. |
| `schedule-manifest.json` | Sequence ranges per worker and materialized reading schedule samples. |
| `generation-manifest.json` | Protocol entrypoint, pinned tool policy, worker allocation and retry/acceptance policy. |
| `generation-result.json` | Application-level generation accounting imported from k6 outcomes or another protocol adapter: attempts, retries, accepted unique readings, duplicates, rejects, unknown outcomes and dropped schedule entries. |
| `visibility-observations.json` | Optional committed-visibility samples from the telemetry export API with polling interval, timeouts and censored latency notes. |
| `reconciliation.json` | Logical `(device_id, source_id, recorded_at, metric_name)` comparison, ambiguous-outcome resolution and value-integrity result. |
| `runner-summary.json` | Bounded dry-run runner status and generated evidence paths. |
| `cancellation-report.json` | Guard-triggered stop details, drain policy and restoration scope. |
| `provisioning-state.json` | Resumable API provisioning progress, API ids and scoped cleanup state. |
| `provisioning-summary.json` | Sanitized provisioning or cleanup status. |
| `provisioning-secrets.local.json` | Optional local source-token file when `-RotateSourceTokens` is used; do not commit or copy into reports. |
| `environment-manifest.json` | Sanitized commit, host resources, compose files, Docker observation, rate limits, metric catalog, topic defaults, retention and downstream policy context. |
| `observability-inventory.json` | Source/config inventory of ingestion/telemetry meters, histogram buckets, scrape jobs and PromQL query contracts. |
| `storage-fixture.json` | Resumable 30-day import or storage-only SQL fixture plan, checkpoint windows, checksum seeds and pilot disk estimate. |
| `monthly-trial.json` | Monthly-volume query/write plan with real schema/index/retention inventory requirements, cold/warm query phases and live-write gate. |
| `scaling-trial.json` | Fixed-replica comparison plan plus separate HPA timeline evidence contract. |
| `recovery-trial.json` | Bounded one-fault-at-a-time recovery plan with restoration and reconciliation requirements. |

Secrets, source tokens, raw device credentials and high-cardinality runtime
identifiers must stay outside tracked files and outside sanitized reports.
The fixture manifest may contain high-cardinality test identifiers because it is
local evidence under ignored `artifacts/`; do not copy those identifiers into
Prometheus labels or tracked documentation.
The environment collector is read-only. It does not start containers or query
databases; Docker runtime state is recorded only when already available.

## Current Profile Arithmetic

`current-5` preserves the original five-value comparison profile: 10,000
devices at one reading per 300 seconds using `activePowerW`, `voltageV`,
`currentA`, `energyWh` and `rssi`.

`resume-10` uses the implemented ten-metric contract: `activePowerW`,
`voltageV`, `currentA`, `energyWh`, `rssi`, `temperatureC`, `vibrationMmS`,
`runtimeHours`, `powerFactor` and `frequencyHz`.

For the daily measured window:

| Profile | Readings | Numeric values | Status |
| --- | ---: | ---: | --- |
| `current-5` | 2,880,000 | 14,400,000 | Supported preflight |
| `resume-10` | 2,880,000 | 28,800,000 | Supported preflight |

Warmup, retries and drain do not inflate these totals.

Phase arithmetic is measured-window-only:

| Phase | Ingestion schedule | Notes |
| --- | --- | --- |
| `smoke` | 100 devices, 30-second interval, 300 measured seconds | 1,000 readings; readiness gate, not a capacity claim. |
| `baseline` | 10,000 devices, 300-second interval, 1,800 measured seconds | 60,000 readings for `resume-10`, 600,000 numeric values. |
| `hourly` | 10,000 devices, 300-second interval, 3,600 measured seconds | 120,000 readings / 1,200,000 values for `resume-10`; daily volume is only an extrapolation. |
| `daily` | 10,000 devices, 300-second interval, 86,400 measured seconds | 2,880,000 readings / 28,800,000 values for `resume-10`. |
| `burst` | Same baseline total concentrated into each 30-second burst window | Request/read counts stay equal to baseline; phase offsets are compressed. |
| `stress` | Configured rate multiplier compresses the effective interval | The current 1.5x step uses an effective 200-second interval: 90,000 readings / 900,000 values for `resume-10` over 1,800 measured seconds. |
| `mixed` | Baseline ingestion plus a separate read-workload plan | Ingestion totals stay at baseline; current read mix plans 18,000 latest, 3,600 chart and 30 export requests. |
