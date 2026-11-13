# Design: Detect possible missed required stop service with durable evidence

Status: target-only. The [shared architecture](../../../docs/target/transit-stop-monitoring.md) owns cross-change schemas, semantics, defaults and service boundaries. Do not create competing contracts here.

## Ownership and Implementation Boundary

Follow target architecture sections 7–8. alert-service owns immutable rule revisions and activation receipts; analytics owns evaluator state and outbox, fed by authorized projections and operational observations. Rule type uses a focused typed compiler rather than mapping into the incompatible numeric operator schema or executing arbitrary expressions. Default pilot 30m boundary/10m display are distinct; service needs OPEN plus stationary evidence, exit debounce preserves 30m and event-time lateness is explicit. ARMED does not require stopping or entering 10m. No observed entry, left-censoring, route ambiguity, stale evidence, location uncertainty or a disqualifying gap cannot yield a possible-miss alert. Persist timers/checkpoints/outcome revisions; silence closes UNKNOWN, not exit. Alert inbox uniqueness spans rule revisions for the same physical visit, while state remains revision-pinned. Late corrections append evidence and update one alert; future stop service does not erase a past visit. Rule disable/revocation and maintenance revalidate before notification. Live evaluation and pure replay use the same domain functions; replay defaults to no notification.

## Compatibility and Failure Handling

Existing domain ownership, signed account/source checks, reviewed confirmation and persisted receipts remain authoritative. Add focused application/domain components and ports rather than growing catch-all services. Expose typed bounded diagnostics, keep confirmed resources on partial failure, preserve original event times and separate unknown evidence from negative observations. Changes to APIs/events/storage require owning migrations/contracts and narrow tests before broad validation.

## Validation and Rollout

Use [acceptance scenarios](../../../docs/target/transit-stop-monitoring-acceptance.md) and its versioned manifest. Deterministic test doubles establish contracts, not real-model quality or physical feed completeness. Keep new writers/evaluation/activation opt-in until this change's hard invariants pass; general AI release still depends on existing A5/V/R/A6 gates. Update current documentation only as behavior becomes implemented. Implementation task checkboxes stay open until code and evidence exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
