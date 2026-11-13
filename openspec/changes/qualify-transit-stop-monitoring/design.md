# Design: Qualify the transit monitoring vertical scenario and model profiles

Status: target-only. The [shared architecture](../../../docs/target/transit-stop-monitoring.md) owns cross-change schemas, semantics, defaults and service boundaries. Do not create competing contracts here.

## Ownership and Implementation Boundary

Canonical corpus and thresholds are in docs/target/transit-stop-monitoring-acceptance.md and the scenario manifest. This change owns the harness implementation/evidence, not another evaluator or assistant. Use independent fixture labels and actual owner DB/API receipts to grade outcomes; writer masks/explanations are not the oracle. Fixed source IDs/route labels in synthetic tests are never represented as a currently running HSL bus. Observe a real bus passively only for feed/schema/capture verification; do not require a physical driver violation. Replay/synthetic runs default to no external notifications and separate credentials/resources. Declare candidates, profiles, hashes, prices, trials and budgets before a paid trial. All blocked/failed/unreached steps remain in denominators. Preserve historical A5/A6 reports and do not award their completion without their own full criteria. Proposed new thresholds cannot weaken existing stricter gates.

## Compatibility and Failure Handling

Existing domain ownership, signed account/source checks, reviewed confirmation and persisted receipts remain authoritative. Add focused application/domain components and ports rather than growing catch-all services. Expose typed bounded diagnostics, keep confirmed resources on partial failure, preserve original event times and separate unknown evidence from negative observations. Changes to APIs/events/storage require owning migrations/contracts and narrow tests before broad validation.

## Validation and Rollout

Use [acceptance scenarios](../../../docs/target/transit-stop-monitoring-acceptance.md) and its versioned manifest. Deterministic test doubles establish contracts, not real-model quality or physical feed completeness. Keep new writers/evaluation/activation opt-in until this change's hard invariants pass; general AI release still depends on existing A5/V/R/A6 gates. Update current documentation only as behavior becomes implemented. Implementation task checkboxes stay open until code and evidence exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
