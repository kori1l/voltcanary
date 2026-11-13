# Design: Add versioned transit reference and required-stop policies

Status: target-only. The [shared architecture](../../../docs/target/transit-stop-monitoring.md) owns cross-change schemas, semantics, defaults and service boundaries. Do not create competing contracts here.

## Ownership and Implementation Boundary

device-service owns provider catalogue imports, immutable TransitSnapshot and StopServicePolicy revisions. Use focused import/resolve/policy use cases and outbound provider ports. Public immutable caching does not expose account-owned imports. Operator-configured endpoints/keys remain outside model input. Define import receipts, archive/reference guards and If-Match policy amendments. Validate ZIP paths/decompression budgets, feed relationships and effective dates. Persist source/version/hash/asOf. Normalize HFP topic direction 1/2 to GTFS 0/1 and resolve operating day, local scheduled start and >24h GTFS times in Europe/Helsinki. Ambiguous matches return candidates/TRIP_UNRESOLVED. Support provider cancellation/detour evidence with freshness; failure cannot silently produce an empty complete stop list. Required stops must be selected/approved by the user or supported by an imported authoritative operational policy; no inferred all-stop mandate.

## Compatibility and Failure Handling

Existing domain ownership, signed account/source checks, reviewed confirmation and persisted receipts remain authoritative. Add focused application/domain components and ports rather than growing catch-all services. Expose typed bounded diagnostics, keep confirmed resources on partial failure, preserve original event times and separate unknown evidence from negative observations. Changes to APIs/events/storage require owning migrations/contracts and narrow tests before broad validation.

## Validation and Rollout

Use [acceptance scenarios](../../../docs/target/transit-stop-monitoring-acceptance.md) and its versioned manifest. Deterministic test doubles establish contracts, not real-model quality or physical feed completeness. Keep new writers/evaluation/activation opt-in until this change's hard invariants pass; general AI release still depends on existing A5/V/R/A6 gates. Update current documentation only as behavior becomes implemented. Implementation task checkboxes stay open until code and evidence exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
