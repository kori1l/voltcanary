# Device variable implementation evidence

Verification date: 2026-10-17. The six changes implement the first numeric
variable scope. Checked tasks refer to implementation and the evidence below;
the six changes were reverified and archived on 2026-10-20.

## Automated evidence

The 2026-10-20 identity-hardening regression repeated the full Java 21 Docker
fixture using real registration/login and bearer JWTs. Base telemetry/formula,
threshold alerts and duplicate acceptance passed, followed by restart/replay,
pinned revisions, backfill, maintenance, typed dashboard queries, two ingestion
replicas with shared deduplication/failover, disabled-processing recovery and
foreign/source/workspace/cached-access denial. The separate identity fixture
passed 68 gateway/direct-port checks with two accounts and scoped cleanup.
Evidence is in ignored `artifacts/identity-*-docker.log` and the corresponding
smoke summaries. The opt-in flags remain disabled by default.

- Device variable contract suite runs against H2 and PostgreSQL 16. It checks
  owner isolation, immutable publication, expected versions, retained inputs,
  type changes, future defaults, ambiguity, explicit rollout/resume, intervening
  manual edits and archive disable intents.
- Ingestion projection tests check exact generation/digest identity, conflicting
  snapshots, independent projections, bounded inputs and old event compatibility.
- Telemetry variable tests run on H2 and TimescaleDB. They check 235 → 22.7,
  replay, pinned revisions, unknown-bundle parking, division failure, fully
  missing input, valid siblings, zero, backfill and identical preview behavior.
  Evaluator tests cover finite values, bounds, conversion and expression budgets.
  PostgreSQL tests inject an outbox failure and verify transaction rollback and
  retry; concurrent duplicate workers produce one result. Graph tests check real
  extrema timestamps, revision boundaries, typed source identity and no writes.
- Analytics tests check exact thresholds, receipt idempotency, backfill/stale
  suppression, maintenance exclusion and retry after a rule dependency outage.
- Alert admission tests reject queued anomalies after rule edits, disable,
  source revocation or freshness expiry; infrastructure failure stays retryable.
- Console tests check fresh preview, stale responses, source ambiguity, denied
  capabilities, zero versus no data, typed rule payloads and an explicit
  two-source rollout with per-target failure/resume.

The focused variable suite passes nine tests across four files. The final full
frontend suite passes 318 tests across 111 files; typecheck and build pass.
Results are recorded in
`artifacts/variable-frontend-final-*.log`. Backend service suites and Docker
acceptance are recorded in ignored `artifacts/variable-*.log` files. Do not
commit those logs. An earlier broad frontend run timed out in an unrelated ML
settings test; its isolated rerun passed.

## Executable end-to-end fixture

See [operation commands](../operations/device-variables.md). A passed summary at
`artifacts/device-variables-smoke-summary.json` must report preview=22.7,
stored=22.7, secondStored=17.7, a threshold alert and duplicateCount=1.
`dockerServices=true` distinguishes the all-container Java 21 run from the host
Java run against containerized databases and broker.

The all-container fixture passed with Java 21 services, actual PostgreSQL,
TimescaleDB, Redpanda, Redis and the nginx console. Additional executable checks:

- `device-variable-recovery-summary.json`: service restart/replay, new formula
  revision, delayed old-generation event, newer-latest protection, backfill,
  maintenance and a published typed dashboard query.
- `device-variable-replica-summary.json`: two independent ingestion projections,
  shared Redis deduplication, primary failover and durable pending-event recovery
  after a processing-disabled telemetry replica is replaced by an enabled one.
- `device-variable-access-summary.json`: foreign/forged workspace denial, source
  substitution denial, authorization renewal for cached snapshots after account
  revocation, and graph reads without telemetry writes or analytics jobs.

The browser acceptance used real gateway login and the console: create a
variable, preview 235 → 22.7, publish, activate a source, view history, run a
side-effect-free threshold dry run and create a rule. A published dashboard
rendered formula revisions as separate series. The alert inbox displayed the
fixture's actual threshold alert.

## Bounded cost evidence and limits

`VariableTelemetryPostgresTest` processes 50 events and reads them through a
seven-row cursor, checks zero values and executes the actual variable query with
`EXPLAIN (ANALYZE, BUFFERS)`. The recorded fixture run processed these events in
1150.2344 ms, with average lineage row size 332 bytes, retained input JSON size
11 bytes and derived measurement row size 176 bytes. This excludes indexes,
outbox, receipts, raw input rows and infrastructure. It is functional fixture
evidence, not a production throughput or fleet storage estimate.

Automatic lineage/bundle cleanup and workspace-console variable-picker parity
are future extensions. Existing owner selection and authorized typed workspace
reads are supported. Production capacity and retention policy need their own
explicit operating profile. The three activation/processing/alert flags remain
disabled by default.
