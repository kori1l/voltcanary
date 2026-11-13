# Design: Add reusable account-owned spatial zones and revisioned zone sets

Status: target-only. The [shared architecture](../../../docs/target/transit-stop-monitoring.md) owns cross-change schemas, semantics, defaults and service boundaries. Do not create competing contracts here.

## Ownership and Implementation Boundary

device-service owns SpatialZone/ZoneSet immutable revisions; analytics consumes immutable authorized projections. Start with WGS84 circles and bounded paged sets, not arbitrary polygons. Retain circle geometry compatibility where practical, but do not apply the legacy 50-zone per-device cap to a hidden truncated transit list: report limits before import and allow an explicit bounded subset. If-Match protects updates, idempotency keys protect creations, and reference guards prevent deleting pinned revisions. Explicit owner-authorized import assigns new canonical zone IDs with provenance; old telemetry monitoring settings and history semantics remain intact. Frontend draws server geometry and calls shared APIs. Geometry/setting changes invalidate unconfirmed reviews and do not silently alter in-flight visits. Resource/grant revocation prevents further use and evidence reads.

## Compatibility and Failure Handling

Existing domain ownership, signed account/source checks, reviewed confirmation and persisted receipts remain authoritative. Add focused application/domain components and ports rather than growing catch-all services. Expose typed bounded diagnostics, keep confirmed resources on partial failure, preserve original event times and separate unknown evidence from negative observations. Changes to APIs/events/storage require owning migrations/contracts and narrow tests before broad validation.

## Validation and Rollout

Use [acceptance scenarios](../../../docs/target/transit-stop-monitoring-acceptance.md) and its versioned manifest. Deterministic test doubles establish contracts, not real-model quality or physical feed completeness. Keep new writers/evaluation/activation opt-in until this change's hard invariants pass; general AI release still depends on existing A5/V/R/A6 gates. Update current documentation only as behavior becomes implemented. Implementation task checkboxes stay open until code and evidence exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
