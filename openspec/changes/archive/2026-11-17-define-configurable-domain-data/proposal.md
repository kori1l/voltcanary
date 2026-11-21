# Change: Typed configurable collections and temporal relations

Status: G1 owner delivery verified, 2026-11-17. The native data owner
implements reviewed typed schema and row writes, LIVE and sealed reads,
event-time lookup and splitting, captured history, validated imports, reviewed
schema/index migrations and archive, and workspace field/row grants. Signed
Compose scenarios cover migration, archive, import replay and scoped reads.
Verification evidence is recorded in [tasks.md](tasks.md). External adapters
and broader UI/AI composition have their own program changes.
This is a generic platform capability, not a specialized transit workflow.

## Why

The platform must compose many company-specific scenarios from typed data, relations, observations, geometry and rules. A separate controller/evaluator branch for every business scenario would defeat that goal.

## What Changes

Add account-scoped collection schemas, immutable row/schema revisions, typed resource references, effective-dated relations, bounded queries and reviewed imports. Reuse existing canonical business resources through bindings; do not duplicate devices or expose raw DDL/SQL.

## Dependencies

No prerequisite from the transit queue. Reuse existing account/workspace/device/source/variable/schedule identities.

## Design and Impact

Complete contracts now include VCL types/methods, efficient dense native storage,
customer DB adapters and strict imports; see the linked normative documents in
design.md. G7/UI/acceptance participate in each vertical slice, not only at the end.

[Configurable platform architecture](../../../../docs/target/configurable-operations-platform.md) and [authoritative program](../../README.md#configurable-operations-program). Reuse current services, variables, operational schedules/facts, dashboards and assistant orchestration. The platform is pre-production: obsolete APIs/data formats may be replaced and scoped local data rebuilt, while supported functionality remains. Implementation requires owning migrations/contracts and focused tests.

## Out of Scope

No paid/live campaigns, arbitrary executable SQL/scripts, silent source/identity substitution, duplicate assistant/ledger, automatic publication or legal-policy assumptions. Existing failed A5/A6 gates remain open.

## Current checkpoint

[Implemented owner contract](../../../../docs/current/api/configurable-native-data.md).
V024/V025 add the schema/provisioning/review/receipt/outbox stores. Physical scalar
values belong to dense typed tables, not metadata JSON/EAV. Actual PostgreSQL/H2
catalog checks determine READY. Signed API tests cover confirmation, replay,
lost-completion reconciliation, account ownership and account deletion fencing.
This increment does not close the full storage/performance/row/import tasks.

