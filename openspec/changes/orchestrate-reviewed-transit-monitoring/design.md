# Design: Orchestrate reviewed transit monitoring with bounded research and model routing

Status: target-only. The [shared architecture](../../../docs/target/transit-stop-monitoring.md) owns cross-change schemas, semantics, defaults and service boundaries. Do not create competing contracts here.

## Ownership and Implementation Boundary

Coordinator schedules registered skills with typed DAG, goals and one-level worker jobs. No direct recursive worker calls; at most two independent read-only jobs in parallel. External documents are untrusted evidence with provenance/limits, not executable commands. Known GTFS/HSL resolution is deterministic provider I/O and does not consume a model call. Bound model-assisted research separately but charge any actual provider calls to the existing parent ledger. PINNED continues unchanged; AUTO chooses authorized qualified profiles by skill/phase and pins each chosen model before I/O. No hidden escalation, missing price economics, per-point LLM or SDK-owned mutation loop. A2 remains the sole reservation owner. Reuse Spring AI transport/schema tools and existing semantic compiler. A question from a worker becomes one persisted coordinator question with actual choices. Each mutation uses scoped review/version/digest, reauthorization and stable receipt; creation followed by failed connection reuses the same device. Activation is separate by default; bounded bundle consent must enumerate effects/fan-out and invalidate on material changes. Saved request replay authenticates identity/content before model resolution. Device type/location provenance is explicit and catalogue membership alone cannot authorize a user-only choice. Source-success receipts, not proposed IDs, ground dashboards and rules.

## Compatibility and Failure Handling

Existing domain ownership, signed account/source checks, reviewed confirmation and persisted receipts remain authoritative. Add focused application/domain components and ports rather than growing catch-all services. Expose typed bounded diagnostics, keep confirmed resources on partial failure, preserve original event times and separate unknown evidence from negative observations. Changes to APIs/events/storage require owning migrations/contracts and narrow tests before broad validation.

## Validation and Rollout

Use [acceptance scenarios](../../../docs/target/transit-stop-monitoring-acceptance.md) and its versioned manifest. Deterministic test doubles establish contracts, not real-model quality or physical feed completeness. Keep new writers/evaluation/activation opt-in until this change's hard invariants pass; general AI release still depends on existing A5/V/R/A6 gates. Update current documentation only as behavior becomes implemented. Implementation task checkboxes stay open until code and evidence exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
