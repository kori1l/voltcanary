## 1. Implementation and verification

- [x] 1.1 Implement authorized combined variable descriptors with origin/unit/revision/activation/capability and historical entries.
- [x] 1.2 Add unambiguous variableId/source selectors to bounded history/latest/chart/export while preserving legacy APIs.
- [x] 1.3 Add source/variable/time indexes and stable pagination/latest tie-breaking with PostgreSQL query-plan evidence.
- [x] 1.4 Implement bounded SQL reducers/buckets with sample counts, gaps and revision segmentation, without persisting graph-only metrics.
- [x] 1.5 Extend dashboard typed manifests/owner reads and source-aware validation for stored variables.
- [x] 1.6 Key/invalidate bounded query caches by authorized scope, identity, revision policy and resolution; renew authorization.
- [x] 1.7 Verify mixed revisions, late events, zero versus missing, source ambiguity, export cursor and archive/history.
- [x] 1.8 Verify unauthorized and revoked cache reads fail closed and dashboard queries cause no telemetry writes or jobs.
- [x] 1.9 Document actual read contracts/query limits and legacy temperature/GPS/energy compatibility evidence.
- [x] 1.10 Run telemetry/dashboard focused checks, structure and strict OpenSpec validation.

## Implementation evidence

See [current verification](../../../docs/current/flows/device-variable-verification.md),
[current architecture](../../../docs/current/architecture/device-variables.md) and
[operation commands](../../../docs/current/operations/device-variables.md).
All ten tasks are implemented and verified for the declared first-version scope. This change remains active for review.
