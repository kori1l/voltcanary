# Implementation and Verification

## 1. Full owner delivery gates

- [ ] 2.1 Implement and verify dense SQL storage, optional history/index features, snapshot/CAS mutations and sealed import contracts; include PostgreSQL catalog/lock/performance evidence.

- [ ] 1.1 Specify schema/row/ref/query/import/revision contracts, scope, bounded indexes and canonical resource bindings.
- [ ] 1.2 Implement generic metadata/row/relation persistence, permission-aware bounded queries and immutable revisions.
- [ ] 1.3 Implement reviewed import previews/receipts, migration impact and archive/reference guards.
- [ ] 1.4 Test alternate customer schemas, field access, temporal cardinality, idempotency and schema migrations.
- [ ] 1.5 Update implemented contracts and validate focused device/storage tests, structure and OpenSpec.


- [ ] 3.1 Implement nullable clear, business/system key contracts, generation head fences and A51/A57/A58/A65 PostgreSQL tests.

## 4. Native owner increments

- [x] 4.1 Implement and verify signed native collection reservation/review/apply/reconcile, typed table provisioning, scoped describe/real compiler metadata and account deletion fencing on H2/PostgreSQL.
- [x] 4.2 Implement and verify reviewed single-row INSERT/UPDATE/DELETE, exact typed codec, generation/dataVersion/row CAS, atomic receipt/history/outbox, indexed reference guards and bounded signed LIVE seek queries on H2/PostgreSQL.
- [x] 4.3 Seal bounded native query results and page them by snapshot ID with current actor/scope/schema fences, absolute expiry, quota and corruption checks.
- [x] 4.4 Build an exact key and effective-interval index at seal time and expose signed event-time lookup at the captured data version. Verify half-open boundaries, overlap, late assignments and projection mismatches on H2/PostgreSQL.
- [x] 4.5 Split a finite interval against the same sealed index and preserve gaps, overlaps and the original snapshot cutoff on H2 and PostgreSQL.
- [x] 4.6 Prepare analytics lookups from an owner-verified manifest for each declared node. Bind manifests to compilation, preserve replay across LIVE writes and recheck grant and scope before and after owner reads.

## Checkpoint and next work

The native schema and reviewed row/CAS/LIVE owner increments are complete in code.
Their owner contract and exact verification boundary are in
[the current API](../../../docs/current/api/configurable-native-data.md).
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
SNAPSHOT querying, imports/staged generation replacement, temporal relations,
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

Next: actual G0 owner/bundle wiring and shared AI M1 acceptance. Do not add customer-specific
tables, raw user SQL, specialized transit branches or synthetic owner readiness.
