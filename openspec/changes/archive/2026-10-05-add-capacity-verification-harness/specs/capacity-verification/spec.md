## ADDED Requirements

### Requirement: Capacity Profiles Preserve Claim Semantics

The test harness SHALL validate versioned profiles and distinguish device count,
readings, numeric values, requests, connection count and duration. Unsupported
numeric metric names SHALL block the requested profile without silently changing it.

#### Scenario: Resume workload is selected against the implemented ten metrics

- **WHEN** resume-10 requires ten numeric values and all ten names are supported
- **THEN** preflight SHALL pass profile validation before load generation
- **AND** a current-5 result SHALL NOT satisfy the resume-10 claim.

#### Scenario: Unsupported metric profile is selected

- **WHEN** a profile includes a numeric metric name outside the supported catalog
- **THEN** preflight SHALL report BLOCKED before load generation
- **AND** no fixture preparation or generation SHALL start for that profile.

#### Scenario: Daily arithmetic is evaluated

- **WHEN** 10,000 devices send once every 300 seconds for 24 measured hours
- **THEN** the schedule SHALL contain 2,880,000 unique readings
- **AND** ten numeric values per reading SHALL imply 28,800,000 expected values
- **AND** warmup, retries and drain SHALL NOT inflate these totals.

#### Scenario: Local hourly measurement is selected

- **WHEN** the user chooses an hourly local run with 10,000 devices and ten values per reading at 300-second intervals
- **THEN** the measured schedule SHALL contain 120,000 readings and 1,200,000 values over 3,600 seconds
- **AND** any daily projection SHALL use reconciled observed totals and actual measured duration and be labeled an extrapolation
- **AND** 24-hour endurance and populated monthly storage SHALL remain separate optional trials rather than prerequisites for hourly evidence.

### Requirement: Load Generation Is Reproducible And Observable

The harness SHALL provision isolated fixtures through supported APIs, await
source readiness and drive an independent arrival schedule with explicit
protocol, seed, worker allocation, effective limits and retry policy.

#### Scenario: A provisioned source has stale authentication state

- **WHEN** an ingestion replica knows a data-source ID but its cached token prefix does not match the locally persisted rotated token
- **THEN** the full-cohort readiness barrier SHALL report the source as stale
- **AND** generation SHALL remain BLOCKED until every configured replica matches.

#### Scenario: Generator cannot keep the requested schedule

- **WHEN** the generator cannot start scheduled readings
- **THEN** it SHALL record dropped operations and actual achieved traffic
- **AND** it SHALL NOT report the complete requested daily workload as passed.

#### Scenario: A timed out request is retried

- **WHEN** acceptance is ambiguous after a timeout
- **THEN** the retry SHALL preserve the logical reading identity and timestamp
- **AND** attempts, unique readings and unresolved outcomes SHALL be separate.

### Requirement: Persistence Evidence Is Independent Of Transport Success

The harness SHALL compare expected and committed measurement keys and values
after bounded drain and SHALL observe persistence latency independently of
in-process pre-commit timers. Batch and MQTT results SHALL use application-level
accounting instead of equating transport acknowledgement with persistence.

#### Scenario: Counts match but a measurement was substituted

- **WHEN** a stored key or value differs despite matching total row counts
- **THEN** reconciliation SHALL identify the discrepancy and fail integrity.

#### Scenario: Polling cannot observe a sampled reading within its deadline

- **WHEN** a persistence sample times out
- **THEN** the report SHALL retain the timeout and polling uncertainty
- **AND** it SHALL NOT discard the sample to improve latency percentiles.

### Requirement: Monthly Capacity Requires Populated Storage Evidence

When a monthly-capacity extension is selected, it SHALL require populated storage
evidence with disk guards and measured table/index/auxiliary costs. Storage-only
loads SHALL be labeled separately from API ingestion and continuous operation.
The selected local hourly scope SHALL leave this claim `NOT_RUN` without blocking
the hourly verdict.

#### Scenario: Monthly population is not selected

- **WHEN** the selected scope is the local hourly run
- **THEN** the harness SHALL NOT load 864 million values or run for 24 hours
- **AND** monthly storage and daily endurance SHALL remain `NOT_RUN`
- **AND** their absence SHALL NOT block an evidence-backed hourly verdict.

#### Scenario: Only a small pilot dataset has been loaded

- **WHEN** a pilot is extrapolated to 864 million values
- **THEN** the report SHALL label size and capacity as estimates
- **AND** the full monthly-volume claim SHALL remain unverified.

#### Scenario: Full volume is prepared

- **WHEN** 864 million numeric values are loaded over the target timestamp range
- **THEN** the harness SHALL verify their integrity and deployed schema/index settings
- **AND** it SHALL measure supported query workloads alongside live writes.

### Requirement: Scaling And Recovery Results Are Scoped To Tested Topology

The harness SHALL compare replicas under recorded resource and partition settings,
separate HPA behavior from fixed-replica performance, and restore injected faults
within bounded runs. Accepted readings SHALL reconcile after recovery.

#### Scenario: More replicas are started without a throughput improvement

- **WHEN** replica count increases but sustainable throughput does not improve
- **THEN** the report SHALL describe the measured limit and resource changes
- **AND** it SHALL NOT infer scaling performance merely from replica count.

#### Scenario: Single database process restarts successfully

- **WHEN** the test only restarts a single database process
- **THEN** the result SHALL be scoped to restart recovery
- **AND** it SHALL NOT establish replicated HA or backup restore guarantees.

### Requirement: Runs Produce Bounded And Auditable Results

The runner SHALL enforce configurable time/resource/error guards, preserve
sanitized evidence on interruption, scope cleanup to its fixture manifest and
produce claim-level PASS, FAIL, BLOCKED, INCONCLUSIVE or NOT_RUN verdicts.

#### Scenario: A guard stops a run

- **WHEN** a disk, error, lag or time guard is exceeded
- **THEN** generation SHALL stop and fault targets SHALL be restored
- **AND** a bounded drain and incomplete-run evidence SHALL be recorded
- **AND** unrelated data SHALL NOT be deleted.

#### Scenario: Evidence for a claim is absent

- **WHEN** a required phase or measurement was not completed
- **THEN** the report SHALL identify the missing evidence
- **AND** it SHALL NOT mark that claim PASS or rewrite calculated claims as measured.
