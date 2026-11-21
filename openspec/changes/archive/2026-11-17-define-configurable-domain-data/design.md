# Design: Typed configurable collections and temporal relations

The [shared configurable architecture](../../../../docs/target/configurable-operations-platform.md)
owns canonical semantics and service boundaries. Native schema/row/query slices
and bounded atomic imports are implemented; full imports/migrations/relations
and sharing remain target work.

## Implementation-first additions awaiting qualification

V035 journals validated100-row chunk transitions with durable progress CAS,
per-chunk receipts/outbox, cancellation and same-job recovery; the source bound
is10000 rows/1 MiB and sealed plans are capped at16 MiB. Obsolete pre-production
import control records are reset by that migration, without deleting native rows.

V036 adds reviewed schema/index migrations and archive. New field IDs are
reserved; retained IDs cannot change type/unit/reference meaning. Explicit fills
and registered conversions prepare complete bounded target rows before any DDL.
Real analytics deployments/bundles, grant/in-flight review state and dashboard
definitions provide separate dependency cuts. Unavailable/incomplete owners fail
closed. Cuts are refreshed after shadow provisioning; actual schema/generation
admission remains each dependent owner's responsibility, without distributed
atomicity claims. The native account/head fence switches immutable schema/field/
index metadata, physical generation, result and outbox together. Exact READY
contents are verified rather than repaired. Old schemas/generations stay retained;
sealed query reads now load their own immutable schema. Archive requires a
prepared review, dependency/reference guards and no active snapshot/running import.

V037 adds owner-reviewed immutable workspace grants with explicit readable/
writable fields, ALL or bounded stable row IDs, USER/TEAM subjects and separate
READ/INSERT/UPDATE/DELETE actions. The existing workspace account consent,
identity membership and canonical resource rights remain authoritative.
Shared operations reuse the existing compiler/SQL/snapshot/CAS/receipt engines
with an explicit actual-actor context. Projection/filter/order are field-scoped;
row predicates precede paging and temporal lookup. Scope digests bind grant,
membership, association and active account state. Writes also pin canonical
resource permissions and check current native reference grants. No owner
impersonation, broad grant union or thread-local authority is introduced.
Grant revocation/creation invalidates saved scopes. Active grants block reviewed
schema/archive changes until the owner explicitly revokes them.

These source additions remain uncompiled/unqualified under the user's
features-first policy. They do not close the full G1 access, history, retention,
performance or UI/AI acceptance gates. The API contract records routes and limits.

## Implementation Boundary

**Normative complete design (2026-11-03):**
Native physical layout is typed table per collection, managed by a trusted metadata compiler; no user DDL and no EAV value store. History/projection/relations are optional. EXTERNAL tables remain customer-owned; GX owns connection protocol. Query snapshot/consistency, schema migration/index jobs and CAS receipts follow the normative storage contract.

Contracts: [VCL](../../../../docs/target/configurable-operations-language.md),
[storage/adapters](../../../../docs/target/configurable-operations-storage.md),
[strict imports](../../../../docs/target/configurable-operations-imports.md) and
[acceptance A01–A66](../../../../docs/target/configurable-operations-acceptance.md).
These decisions supersede tentative storage/interpreter wording below.

device-service owns configurable metadata and operational rows through a focused module and ports. Publish schema migration impact before changing dependent mappings/rules/dashboards. Enforce row/field access in filters, counts and joins; missing, ambiguous and unauthorized data are distinct typed outcomes. Separate pinned configuration revisions from effective-at-event-time mutable relation rows, recording all resolved row revisions.

## Compatibility and Validation

Preserve supported functionality, canonical resource ownership, signed access
and reviewed confirmation/idempotency semantics. Per the user's2026-11-17
pre-production policy, compatibility with obsolete APIs, persisted formats and
saved artifacts is not an acceptance gate. Replace obsolete contracts instead
of maintaining parallel legacy implementations; scoped local data may be
migrated, rebuilt or reset. Current-operation CAS, reference/access checks and
the consistency of snapshots actually used by supported workflows still apply.
New schemas/operators must be advertised only when implemented. Preview and
runtime use the same current contracts. Require independent cross-domain,
negative, replay and access tests; a model explanation is not completion evidence.
Keep implementation tasks unchecked until code and verification exist.


## Implementer clarification revision 2

The [owner control protocol](../../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.

## Native schema owner checkpoint

device-service/configurable separates schema types, owner application workflows,
ports and JDBC/DDL adapters. NativeSchema uses common-vcl rather than another type
checker. NativeSqlLayout maps UUIDs to allowlisted dense columns; only selected
indexes/history tables are allocated. The catalog journals PROVISIONING before
DDL and verifies actual types/precision/nullability/index readiness before READY.
A READY table cannot be silently recreated on retry. PostgreSQL jobs fence on
the account/dataset rows and use bounded lock/statement timeouts.

The collection review owns server-assigned resource/field/generation/system-key/
receipt IDs, exact digest/revision, original expiry and current account authority.
APPLYING/UNKNOWN receipt precedes provisioning. Same-ID reconciliation inspects
the stored result and never extends expired authority. Result receipt, review
completion and one local outbox intent commit together. Account deletion flushes
the authority fence before archiving datasets/cancelling unfinished reviews.

Actual NativeDatasetMetadata supplies owner SQL snapshots to common compilation;
VclDatasetDescription is the shared wire DTO. Public /data routes require signed
actors; internal describe requires the existing service token and fresh actor
scope. This is an initial owner adapter, not full G0 RULE binding/grant readiness.
Current descriptions keep liveQueryReady/historyReady false. See the current API
document for implemented routes and remaining gates.

## Sealed native query snapshots

An explicit bounded owner job captures the full result of the existing LIVE SQL
profile while holding the account and dataset fences. V028 stores the canonical
typed result and payload digest with the actor, scope, schema, captured head,
query digest and absolute expiry. Snapshots cap result size and count per
account. SNAPSHOT paging reads only the saved result after fresh authorization.
The signed cursor binds the snapshot ID and original expiry. Account deletion
removes all snapshots under the same account fence.

The first event-time owner lookup resolves typed key equality and half-open
intervals within one sealed selection. The seal pins the projected key fields
and interval field and writes the index in the same transaction. Its recorded
cutoff is the captured data version. Missing, ambiguous or unknown intervals
produce explicit UNKNOWN results. Mutable history and analytics operator
integration remain open under the full temporal requirement.

## Bounded atomic import owner

V033 persists scoped immutable uploaded bytes and reviewed validation plans.
NativeImportInput owns bounded strict CSV/JSONL and explicit typed conversion;
NativeImportPlans seals reserved row IDs, business-key matches, row CAS and safe
diagnostics using the shared compiler and existing row codec. JdbcNativeImports
owns account/head fences, artifact/plan checksums, review TTL and idempotency.
Apply revalidates every mutation before any write, then commits all dense row
writes/history, one head increment, saved batch receipt and one local outbox
intent. A failed transaction leaves the original prepared review available.
Receipt inspection never dispatches. The profile supports at most 500 rows and
1 MiB artifacts/plans; chunked imports,
migrations and workspace/field sharing remain separate full-owner gates.
The current API documents exact routes and conservative capability boundaries.

## Reviewed native generation replacement

V034 backfills existing datasets to their original physical UUID and journals
each owned generation. NativeSqlLayout accepts the actual NativeDataset head;
row reads/writes, reference checks, catalog verification and history always use
its trusted physical pointer. Public/compiler metadata retains logical identity
and generation without exposing physical names.

STAGED_REPLACE seals the complete bounded source and removed-row set. After
confirmation, NativeImportStages reconciles reserved shadow DDL outside active
data transactions, then atomically populates and marks READY. NativeStageRows
verifies exact rows, typed values, versions, timestamps and optional history.
The account/head fence orders writers and cutover. Actual old row versions,
incoming indexed references and the READY stage are rechecked before pointer,
journal, history tombstones, index manifest, receipt and outbox commit together.
Old generations remain retained; snapshots continue reading their sealed values.
The bounded native profile is not a historical query/retention worker or an
external-generation swap implementation.

## Batched native reference imports

NativeImportReferences preloads real account-scoped owner schemas and resolves
the ordered conversion chains in bounded rounds. Lookup keys are deduplicated,
selected columns contain only key/identity/version values, and each SQL batch
has at most500 keys. Checked identities supply mutation authorization through
the same connection and account/head fences. Plans seal owner schema/generation/
head and exact resolved key/record/version evidence; metadata pins remain even
for all-NULL mappings. Before target effects, fresh batch reads must agree with
every pin. This extends the original import/reference gate1.3, not a new feature
outside G1. Actual workspace/field sharing and external reference adapters remain
their unfinished owner gates.
