# Design: Typed configurable collections and temporal relations

The [shared configurable architecture](../../../docs/target/configurable-operations-platform.md)
owns canonical semantics and service boundaries. The first native schema slice
is implemented; unqualified rows/query/import/relations remain target work.

## Implementation Boundary

**Normative complete design (2026-11-03):**
Native physical layout is typed table per collection, managed by a trusted metadata compiler; no user DDL and no EAV value store. History/projection/relations are optional. EXTERNAL tables remain customer-owned; GX owns connection protocol. Query snapshot/consistency, schema migration/index jobs and CAS receipts follow the normative storage contract.

Contracts: [VCL](../../../docs/target/configurable-operations-language.md),
[storage/adapters](../../../docs/target/configurable-operations-storage.md),
[strict imports](../../../docs/target/configurable-operations-imports.md) and
[acceptance A01–A66](../../../docs/target/configurable-operations-acceptance.md).
These decisions supersede tentative storage/interpreter wording below.

device-service owns configurable metadata and operational rows through a focused module and ports. Publish schema migration impact before changing dependent mappings/rules/dashboards. Enforce row/field access in filters, counts and joins; missing, ambiguous and unauthorized data are distinct typed outcomes. Separate pinned configuration revisions from effective-at-event-time mutable relation rows, recording all resolved row revisions.

## Compatibility and Validation

Preserve canonical resource IDs, signed access, existing arithmetic AST v1, current circle endpoints and saved reviewed plans. New schemas/operators must be versioned and advertised only when implemented. Preview and runtime use the same canonical contracts. Require independent cross-domain, negative, replay and access tests; a model explanation is not evidence of completion. Keep implementation tasks unchecked until code and verification exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.

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
