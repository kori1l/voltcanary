# Implementation and Verification

Execution policy: finish the remaining agreed features before starting the
change-wide verification campaign. Implemented but unqualified items stay
unchecked; record their implementation state in the checkpoints below.

## 1. Full owner delivery gates

- [x] 2.1 Implement and verify dense SQL storage, optional history/index features, snapshot/CAS mutations and sealed import contracts; include PostgreSQL catalog/lock/performance evidence.

- [x] 1.1 Specify schema/row/ref/query/import/revision contracts, scope, bounded indexes and canonical resource bindings. See the current API and the normative contracts linked from the design.
- [x] 1.2 Implement generic metadata/row/relation persistence, permission-aware bounded queries and immutable revisions.
- [x] 1.3 Implement reviewed import previews/receipts, migration impact and archive/reference guards.
  - [x] 1.3.1 Implement bounded native atomic CSV/JSONL artifacts, complete typed validation, confirmed INSERT/UPSERT and exact receipt/outbox replay. Verification: atomic import checkpoint below.
  - [x] 1.3.2 Implement reviewed native STAGED_REPLACE with stable IDs, exact shadow verification, deletion/reference impact, atomic cutover and retained snapshots. Verification: generation replacement checkpoint below; key/head acceptance also closes3.1.
  - [x] 1.3.3 Implement ordered batch resolve_ref with owner/version pins, complete safe error counts and fresh apply/cutover revalidation. Verified on H2/PostgreSQL and through the signed Compose API.
  - [x] 1.3.4 Implement completely validated chunked import progress, exact per-chunk outcomes, cancellation and same-job recovery. Verified on H2/PostgreSQL and through the signed Compose API.
  - [x] 1.3.5 Implement reviewed schema/index migration impact and archive/reference guards while preserving pinned revisions. Verified on H2/PostgreSQL and through the signed Compose API.
- [x] 1.4 Test alternate customer schemas, field access, temporal cardinality, idempotency and schema migrations.
- [x] 1.5 Update implemented contracts and validate focused device/storage tests, structure and OpenSpec. The focused storage campaign, 105 OpenSpec validations, structure, and VCL documentation checks pass.


- [x] 3.1 Implement nullable clear, business/system key contracts, generation head fences and A51/A57/A58/A65 PostgreSQL tests.

## 4. Native owner increments

- [x] 4.1 Implement and verify signed native collection reservation/review/apply/reconcile, typed table provisioning, scoped describe/real compiler metadata and account deletion fencing on H2/PostgreSQL.
- [x] 4.2 Implement and verify reviewed single-row INSERT/UPDATE/DELETE, exact typed codec, generation/dataVersion/row CAS, atomic receipt/history/outbox, indexed reference guards and bounded signed LIVE seek queries on H2/PostgreSQL.
- [x] 4.3 Seal bounded native query results and page them by snapshot ID with current actor/scope/schema fences, absolute expiry, quota and corruption checks.
- [x] 4.4 Build an exact key and effective-interval index at seal time and expose signed event-time lookup at the captured data version. Verify half-open boundaries, overlap, late assignments and projection mismatches on H2/PostgreSQL.
- [x] 4.5 Split a finite interval against the same sealed index and preserve gaps, overlaps and the original snapshot cutoff on H2 and PostgreSQL.
- [x] 4.6 Prepare analytics lookups from an owner-verified manifest for each declared node. Bind manifests to compilation, preserve replay across LIVE writes and recheck grant and scope before and after owner reads.

## Checkpoint and next work

The original 13 top-level tasks retain their scope. Nested tasks decompose their
existing acceptance. The current OpenSpec list counts only top-level checkboxes;
nested progress is recorded under its owning parent without changing that total.
New top-level functionality requires an explicit explanation of its added scope.
Close each parent only when its complete acceptance boundary passes. The
delivery checkpoints below retain the implementation and verification evidence.

The native schema and reviewed row/CAS/LIVE owner increments are complete in code.
Their owner contract and exact verification boundary are in
[the current API](../../../../docs/current/api/configurable-native-data.md).
Increment4.2 adds32 cases:13 row/CAS/reference/query cases on each H2/PostgreSQL
backend and3 additional signed HTTP cases on each. Coverage includes two-writer
and same-intent races, rollback after outbox failure, lost commit acknowledgement,
system/business keys, nullable clear/history tombstones, generation fencing,
fresh reference authorization and cursor signature/scope/query/expiry/drift.
Structure, OpenSpec104/104 and VCL documentation checks pass. Logs remain ignored
under artifacts. Full backend verification:1105 tests, zero failures/errors/skips;
all16 reactor modules succeed in g1-native-rows-backend-tests.log. A final focused
16-case H2/PostgreSQL signed API run also passes after formatting and the added
row-review account-deletion assertion (g1-native-rows-final-api.log).
At the4.2 checkpoint, SNAPSHOT querying, imports/staged generation replacement, temporal relations,
migrations, workspace/field sharing, performance evidence and UI/AI remain open.
liveQueryReady advertises the explicit SQL profile; historyReady remains false
until historical querying exists. Broad tasks above deliberately remain unchecked.

G6 now provides the first manual native table/row workbench through these owner
APIs; /data/language publishes the shared compiler registry separately from the
implemented native query profile. AI registration and full UI/AI parity remain open.

V028 adds sealed query snapshots and the opt-in indexed event-time lookup
profile. The 48 focused row and signed owner API tests pass on H2 and
PostgreSQL. The signed owner also supports bounded interval splitting with a
common snapshot manifest. Analytics preparation verifies owner manifests before
compiling and evaluates prepared lookup nodes with fresh grants. The targeted
reactor passed 67 tests on H2, Docker PostgreSQL and signed HTTP fixtures.
Mutable history projection and durable graph execution remain open.

The atomic import checkpoint (task1.3.1, formerly labelled increment4.7) adds the
`native-atomic-import-1` owner profile with immutable uploaded
artifacts, strict CSV/JSONL and explicit conversions. All500 rows can be validated
and applied as one batch. Review digests seal exact row IDs/versions and the
schema/generation/head/scope; apply commits dense rows, opt-in history, one head
version, stored batch receipt and one outbox intent. No rejected or stale review
can overwrite current rows. Reference resolution and chunked imports,
schema migrations and field/workspace sharing remain unsupported. The full
owner tasks above remain unchecked.

The focused86-test run passes with zero failures/errors/skips:10 parser/conversion
cases,29 row/import cases on each H2/PostgreSQL backend and9 signed owner HTTP
cases on each backend. This includes the500-row limit, two concurrent applications,
competing batches, outbox rollback, corruption/expiry, exact receipts and foreign
actor denial. The final Java21.0.10 device reactor passes354 tests, including258
device-service tests, with zero failures/errors/skips across all7 modules
(`artifacts/g1-device-java21-tests.log`). Structure, OpenSpec105/105 and VCL
documentation checks pass. Logs remain ignored under artifacts.

The generation replacement checkpoint (task1.3.2, formerly labelled increment4.8)
adds the V034 physical generation journal and reviewed shadow
replacement. Matching single/composite keys preserve row UUIDs; the exact row
set, versions, values, created timestamps, history and deletion/reference impact
are verified before an atomic pointer/index/receipt/outbox cutover. Retired
generations and sealed snapshots remain retained. READY shadows are not repaired
or recreated after corruption. Actual writer/cutover overlap gives one valid
ordering, and late reviews for old heads fail. Key-only tables use a sealed
matched-row version without weakening the public UPDATE grammar.

The resumed Java21.0.10 device reactor passes374 tests, including278 device-service
tests, with zero failures/errors/skips across all7 modules
(`artifacts/g1-generation-resume-tests.log`). Native imports include39 cases on
each H2/PostgreSQL backend; both signed owner API suites also cover key-only
replacement/UPSERT. A51 reuses typed omission/NULL and dense history cases;
A57/A58 use actual generation replacement, overlapping writer, stable composite
IDs, rollback and snapshot cases; A65 includes missing/nullable composite-key
rejection and actual native storage. This closes3.1, not the full storage,
permission, migration or performance acceptance tasks.

The resumed Compose runtime also passes
`python scripts/native-import-docker-smoke.py` through the ordinary signed
gateway (`artifacts/g1-native-import-runtime-ready-summary.json`). It verifies
atomic insert, generation replacement, stable IDs, same receipt replay, original
sealed snapshot contents, stale-import409 and key-only UPSERT after cutover.
The first attempt ended at the stack-readiness timeout during cold sequential
Maven startup; its failure summary is retained separately. No live model calls
or RULE activation were dispatched. Structure, OpenSpec105/105 and VCL
documentation checks also pass.

Next after that checkpoint: full reviewed migration/import/reference contracts,
sharing, retention/performance acceptance and shared AI M1
acceptance. G0 is already archived. Do not add customer-specific tables, raw user
SQL, specialized transit branches or synthetic owner readiness.

## Implementation-first continuation

Per the user's direction, finish the remaining features before the next
systematic verification campaign. Nested items remain unchecked until that
campaign establishes their full acceptance.

- 1.3.3 implementation is present: batched ordered native reference conversions,
  sealed owner/key/row cuts, metadata-only NULL pins and fresh effect checks.
  The last completed pre-chunked Java21 reactor passed392 tests (296 device
  tests); the subsequent gateway run was deliberately stopped when verification
  was deferred. This evidence does not qualify later chunked/migration work.
- 1.3.4 implementation is present and unqualified: persisted100-row chunk
  transitions for completely validated sources up to10000 rows, progress CAS,
  per-chunk receipts/outbox, same-job recovery, cancellation, bounded receipt
  paging and an owner-authorized scheduled worker. No new test run has started.
  V035 resets obsolete pre-production import control plans/artifacts for the
  new sealed-manifest contract; native table rows/history/snapshots are retained.
- 1.3.5 implementation is present and unqualified: V036 migration reservations,
  closed typed mappings and complete bounded source validation; real analytics/
  grant/dashboard dependency observations; reviewed shadow schema/index cutover,
  exact receipt recovery and archive/reference guards. Sealed query reads now
  resolve their own immutable schema/generation instead of the current LIVE
  schema. No compilation or verification campaign has been run for these additions.
- 1.2 access implementation is present and unqualified: V037 reviewed immutable
  USER/TEAM workspace grants with coherent field/row/action scope; actual actor
  membership/association fences around shared LIVE/sealed/as-of reads; scoped
  reviewed row INSERT/UPDATE/DELETE through the existing CAS/history/receipt
  engine, including actual native/canonical reference permissions. Grant changes
  invalidate saved scopes and active grants block migration/archive. This is
  implementation evidence only; no new checks or test campaign have run.
- V038 adds optional history capture checkpoints, exact row-revision reads and
  bounded reconstruction of an explicit generation and data version. Shared
  reads require HISTORY and enforce current row and field permissions. These
  additions await build and runtime verification.
- Owners can list captured generations and retrieve their immutable schemas and
  version ranges to construct historical queries after a schema change.
- The native table workbench can browse captured generations and read a chosen
  historical version with its immutable schema and cursor. UI verification is pending.
- The workbench also connects CSV/JSONL import and schema migration/archive to
  their existing owner review APIs. It retains the review ID across reload.
- V039 records owner history leases with fixed expiry, exact request replay
  and release. Dataset archive reports and checks outstanding leases. V040
  adds opt-in PostgreSQL reclamation of eligible retired generations under
  the same account fence as lease acquisition and snapshot sealing.
- Final G1 qualification has started. Frontend type checking, production build
  and all 439 tests pass. Strict OpenSpec validation (105 items), structure checks
  and VCL documentation validation pass. Both database engines pass the 51-case
  import and 18-case native row suites. The first Maven reactor stopped at a
  stale worker fixture expectation, which passed the focused rerun after repair.
  The expanded signed Compose scenario passes against PostgreSQL with Flyway
  V041. It covers stale reference-cut rejection, reviewed migration with all
  three dependency owners, original snapshot and historical cut preservation,
  worker completion and two exact chunk receipts, history lease replay/release,
  archive guards and eligible archive/replay, and workspace field/row scope and
  revoke. The restricted real field matches the unknown-field error for the
  recipient while the owner reads its migrated value.
  See `artifacts/g1-final-runtime-fields.json`. The full Maven reactor passes
  all 16 modules and 2,051 tests with no failures, errors, or skips
  (`artifacts/g1-final-backend-rerun.log`). A controlled lost commit
  acknowledgement also passes on H2 and PostgreSQL. The same job returns its
  two original chunk receipts and leaves two outbox events
  (`artifacts/g1-final-recovery-and-insert.log`). The focused catalog, row and
  import campaign passes 159 cases, including PostgreSQL retention with an active
  snapshot, an active lease, and reclamation after release. PostgreSQL
  performance remains open.
  The separate PostgreSQL benchmark is opt-in so ordinary Maven tests do not run
  timed workloads. From `backend/`, run:

  ```powershell
  mvn -pl services/device-service -am '-Dtest=NativeDensePostgresBenchmark#pairedOwnerReads+pairedReviewedInserts' '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```

  The read comparison uses equivalent typed SQL, owner and generation checks,
  typed results and signed seek cursors. The insert comparison times commit of
  already prepared reviews and includes the owner fence, head CAS and durable
  receipt/outbox. Its two workloads use independent seeded tables. Measure the
  full prepare/apply path separately if making a claim about API latency.

  Initial PostgreSQL 16.15 read comparison on 20,000 rows, one client, and
  500 samples per operation after 100 warm-ups exceeds the 20% target. List
  p95 is 26.17 vs 9.06 ms (+189%) and indexed fetch p95 is 24.95 vs 8.42 ms
  (+196%). The captured physical plans use indexes, return 21 and one row,
  respectively, and show zero disk reads on the warm fixture. The result points
  to the adapter path for further profiling. Gate 2.1 stays open.
  See `artifacts/g1-final-postgres-benchmark.log` and
  `artifacts/g1-final-performance-context.json`.
  The initial reviewed insert commit p95 is 40.59 vs 16.01 ms (+154%)
  on the same workload scale (`artifacts/g1-final-recovery-and-insert.log`).
  After consolidating physical catalog reads, combining head locking with
  metadata loading and caching immutable compilation, p95 is 14.28 vs 9.68 ms
  for list (+48%), 13.43 vs 8.89 ms for point fetch (+51%), and 27.47 vs 16.06 ms
  for reviewed insert (+71%). The threshold is still unmet. See
  `artifacts/g1-optimized-postgres.log`. The later receipt-read optimization is
  outside these timings.
  Consolidating the lock and catalog check into one PostgreSQL statement and
  removing round trips for the review response lowers p95 to 11.94 vs 8.69 ms
  for list (+37%), 11.51 vs 8.43 ms for point fetch (+37%), and 23.11 vs 15.86 ms
  for reviewed insert (+46%). All 57 focused cases in that run passed, including
  stored-schema tampering after cache warm-up and protected retention. See
  `artifacts/g1-paired-head-postgres.log`. Further read and write optimizations
  need a fresh paired measurement.
  After removing the insert's pre-read through the unique index and batching
  completion/audit SQL, reviewed insert p95 is 18.93 vs 17.13 ms (+10.5%) for
  the declared prepared-insert workload. See `artifacts/g1-index-audit-postgres.log`.
  List and point reads in that run still exceed the threshold.
  Reusing validated query compilation under the current physical head and
  access scope brings warm list p95 to 10.12 vs 8.95 ms (+13.1%) and point fetch
  p95 to 9.19 vs 8.03 ms (+14.4%). All 55 cases in the focused run pass.
  The paired plans scan indexes for 21 rows and one row, respectively. These are
  one-client, 20,000-row JDBC adapter measurements of repeated reads and of
  prepared insert commit. See `artifacts/g1-cached-query-postgres.log`.
  The history-enabled Compose fixture (`artifacts/g1-history-storage-footprint.json`)
  retains three physical generations across two schema revisions. It has 112
  checkpoint rows and eight sealed history cuts. Each generation's history
  uses an 8 KiB heap and 16 KiB of indexes on this small fixture. The active
  generation uses a 16 KiB dense heap and 48 KiB of dense indexes. These are
  allocated relation sizes at this fixture scale.
  The signed Compose scenario also passes after rebuilding and restarting
  device-service with the final catalog, compilation-cache and audit paths
  (`artifacts/g1-optimized-runtime.json`).
  The final paired run uses PostgreSQL 16.15, 20,000 rows per table, one JDBC
  client, 500 samples per operation after 100 warm-ups, and history disabled.
  The platform and Docker settings are in `artifacts/g1-final-performance-context.json`.

  | Operation | Generic p50 / p95 / p99 (ms) | Typed SQL p50 / p95 / p99 (ms) | p95 overhead |
  | --- | --- | --- | --- |
  | Warm list | 7.67 / 9.11 / 10.25 | 6.64 / 7.99 / 9.27 | 14.0% |
  | Warm indexed fetch | 7.32 / 9.14 / 10.52 | 6.30 / 7.83 / 8.64 | 16.7% |
  | Prepared insert commit | 13.00 / 15.34 / 17.34 | 11.59 / 13.98 / 15.76 | 9.7% |

  The measured p95 overhead stays within 20% for the declared workload. The
  indexed fetch p99 overhead is 21.7%. Mean client-thread CPU time for generic
  versus typed operations is 0.94/0.50 ms for list, 0.88/0.41 ms for fetch, and
  2.97/1.44 ms for insert. The paired insert run advances the WAL by 9,242,576
  bytes across both variants, including warm-ups, preparation and verification.
  Both read plans use indexes and show no physical block reads on the warm
  fixture. See `artifacts/g1-final-paired-performance.log` for raw measurements.
  This evidence covers the declared workload and the prepared commit phase.
