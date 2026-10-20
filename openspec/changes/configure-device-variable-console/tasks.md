## 1. Implementation and verification

- [x] 1.1 Add focused device-type variable list/editor API modules and numeric metadata/source binding controls.
- [x] 1.2 Implement scale/offset/conversion presets and bounded expression editor with unit/input diagnostics.
- [x] 1.3 Integrate shared server preview, stale-preview publication guard and response-race tests.
- [x] 1.4 Implement publish/default/explicit rollout/archive workflow with expected-version conflicts and partial outcomes.
- [x] 1.5 Show desired/applied generations, UNBOUND/PROPAGATING/ACTIVE/ERROR/DISABLED and source-specific failure evidence.
- [x] 1.6 Integrate descriptor-based measured/computed selectors and history/chart/export/threshold-rule navigation.
- [x] 1.7 Add meaningful UI tests for two devices/sources, denied access, no-data/zero, units, stale preview and formula revisions.
- [x] 1.8 Run frontend typecheck/test/build and browser acceptance for the complete 235→22.7→chart→threshold→alert workflow.
- [x] 1.9 Run real broker/database multi-replica/restart/replay/maintenance/backfill/rollback acceptance and declared fixture cost measurements.
- [x] 1.10 Publish current API/event/architecture/operations/verification docs, update target status/indexes and ADR; check required project guidance alignment and validate structure/OpenSpec before archive.

## Implementation evidence

See [current verification](../../../docs/current/flows/device-variable-verification.md),
[current architecture](../../../docs/current/architecture/device-variables.md) and
[operation commands](../../../docs/current/operations/device-variables.md).
All ten tasks are implemented and verified for the declared first-version scope. This change remains active for review.
