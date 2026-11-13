# Design: Preserve vehicle operational events and observation coverage

Status: target-only. The [shared architecture](../../../docs/target/transit-stop-monitoring.md) owns cross-change schemas, semantics, defaults and service boundaries. Do not create competing contracts here.

## Ownership and Implementation Boundary

Connector capture stores each accepted operational observation in a bounded durable spool, separate from LatestPositions. Accept a bounded allowlist of VP/DOO/DOC/ARR/DEP/ARS/PDE/PAS/VJA/VJOUT for exact authorized vehicles. Events without coordinates remain admissible with explicit quality; source occurrence time is preserved. Use stable sourceEventKey based on provider identity/type/time/content, independent of capture session; identical timestamps across event types are not deduplicated together. Ingestion derives account/device/source from existing trusted source authentication, persists the batch acceptance and outbox before durable ACK. Telemetry persists inbox/storage before publishing vehicle.operational.accepted.v1 through an outbox. Reconnect and disk overflow create durable coverage intervals; upstream unknown loss remains unknown. Replay never becomes live measured state merely by changing receivedAt. Pilot bounds and retention must be configurable and measured; never imply an exactly-once physical upstream feed.

## Compatibility and Failure Handling

Existing domain ownership, signed account/source checks, reviewed confirmation and persisted receipts remain authoritative. Add focused application/domain components and ports rather than growing catch-all services. Expose typed bounded diagnostics, keep confirmed resources on partial failure, preserve original event times and separate unknown evidence from negative observations. Changes to APIs/events/storage require owning migrations/contracts and narrow tests before broad validation.

## Validation and Rollout

Use [acceptance scenarios](../../../docs/target/transit-stop-monitoring-acceptance.md) and its versioned manifest. Deterministic test doubles establish contracts, not real-model quality or physical feed completeness. Keep new writers/evaluation/activation opt-in until this change's hard invariants pass; general AI release still depends on existing A5/V/R/A6 gates. Update current documentation only as behavior becomes implemented. Implementation task checkboxes stay open until code and evidence exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
