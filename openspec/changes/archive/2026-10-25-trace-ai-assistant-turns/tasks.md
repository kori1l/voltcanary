## 1. Trace contract and persistence
- [x] 1.1 Define event schema, correlations, lifecycle statuses, payload limits, redaction, retention and trace-write failure semantics; align API/operations docs.
- [x] 1.2 Implement scoped append-only storage/read/export APIs and crash reconciliation linked to conversations and existing model/audit/receipt records.
- [x] 1.3 Instrument context/capability resolution, every provider attempt, sanitization, compilation, validation, review and execution/reconciliation without recording private reasoning.

## 2. Inspection and acceptance
- [x] 2.1 Add shared conversation step timeline/details and safe diagnostic export; render only observed progress.
- [x] 2.2 Test failures before review creation, retries, partial execution, restart, recorder failure, secret redaction, bounded payloads/retention and cross-account denial.
- [x] 2.3 Reproduce a missing-context and rejected-field dialogue in Docker; identify the failing stage from trace alone, verify browser inspection/export and run affected checks plus strict OpenSpec validation.
