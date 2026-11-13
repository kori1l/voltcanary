# Design: Add manual transit monitoring setup and evidence dashboards

Status: target-only. The [shared architecture](../../../docs/target/transit-stop-monitoring.md) owns cross-change schemas, semantics, defaults and service boundaries. Do not create competing contracts here.

## Ownership and Implementation Boundary

Build focused feature modules rather than expanding route-page monoliths. Manual flow chooses exact physical vehicle/trip, imports versioned stops, selects required occurrences, reviews visible geometry/predicate/coverage defaults, runs a side-effect-free dry-run, saves disabled rule and explicitly activates. Lack of historical evidence is WAITING_FOR_DATA and does not claim successful live validation. Read/write conflict and limit errors remain actionable. Extend owner widget manifests to route/zone layers and service-evidence content; reuse DashboardDefinitionPreview/Canvas/WidgetPanel and exact ordinary domain prepare/create/update. Preserve observed door transitions and gaps, never infer closed state from missing data. Server evaluates with browser closed. Private dashboard remains separate from rule activation and publication. Show initial creation digest/receipt equality rather than comparing a later user-edited draft. Do not silently replace existing dashboards.

## Compatibility and Failure Handling

Existing domain ownership, signed account/source checks, reviewed confirmation and persisted receipts remain authoritative. Add focused application/domain components and ports rather than growing catch-all services. Expose typed bounded diagnostics, keep confirmed resources on partial failure, preserve original event times and separate unknown evidence from negative observations. Changes to APIs/events/storage require owning migrations/contracts and narrow tests before broad validation.

## Validation and Rollout

Use [acceptance scenarios](../../../docs/target/transit-stop-monitoring-acceptance.md) and its versioned manifest. Deterministic test doubles establish contracts, not real-model quality or physical feed completeness. Keep new writers/evaluation/activation opt-in until this change's hard invariants pass; general AI release still depends on existing A5/V/R/A6 gates. Update current documentation only as behavior becomes implemented. Implementation task checkboxes stay open until code and evidence exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
