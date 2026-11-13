# Design: Connect customer databases with explicit read projection and write contracts

Normative storage/adapters and import contracts define NATIVE/EXTERNAL/PROJECTED modes. Customer DB remains authoritative; no compulsory full copy, schema migration or per-event remote query. Adapter capabilities describe stable keys, schema fingerprint, pushdown, snapshot/CDC/poll/deletion consistency and verified mutation/CAS/idempotency/status. Write credentials do not automatically qualify writes. A supported remote mutation commits its remote receipt atomically with data; lost replies reconcile the same key. Scope/secret/egress/intersection checks apply to metadata, query, history and output. Connection types without a required capability return explicit unsupported, never fake completeness.

## Binding Decisions

The language/storage/import documents linked in proposal.md are normative and supersede earlier tentative layout/interpreter wording. Target contracts are complete design decisions, not evidence of implemented behavior. Operator/storage/adapter changes require a versioned reviewed amendment and independent acceptance. Existing identities, signed grants, old AST/review compatibility and one assistant ledger remain.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
