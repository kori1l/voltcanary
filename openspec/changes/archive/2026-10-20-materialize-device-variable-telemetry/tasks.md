## 1. Implementation and verification

- [x] 1.1 Extract focused variable evaluation domain/application ports rather than enlarging TelemetryEventProcessor.
- [x] 1.2 Implement affine and bounded arithmetic AST evaluation, declared aliases/unit rules and publication/runtime contract fixtures.
- [x] 1.3 Implement authorized side-effect-free preview with the production evaluator and bounded explicit inputs.
- [x] 1.4 Promote configured observed numeric inputs to first-class measurements without requiring GPS or built-in metrics.
- [x] 1.5 Extend measurement identity/catalog projection and lineage/failure schema; verify legacy uniqueness migration and both database vendors.
- [x] 1.6 Persist measured/derived rows, failures, processed marker and outbox atomically with deterministic replay keys.
- [x] 1.7 Publish bounded accepted-measurements events and keep validated built-in event compatibility/backfill classification.
- [x] 1.8 Verify missing inputs, zero division, nonfinite values, bounds/budgets, valid siblings and retained input evidence.
- [x] 1.9 Verify pinned old revision replay, transaction failure/retry, concurrent duplicate processing and newer-latest protection.
- [x] 1.10 Document telemetry event/storage/preview contracts and rollback flags; run focused backend checks, structure and strict OpenSpec validation.

## Implementation evidence

See [current verification](../../../../docs/current/flows/device-variable-verification.md),
[current architecture](../../../../docs/current/architecture/device-variables.md) and
[operation commands](../../../../docs/current/operations/device-variables.md).
All ten tasks are implemented and verified for the declared first-version scope. This change remains active for review.

