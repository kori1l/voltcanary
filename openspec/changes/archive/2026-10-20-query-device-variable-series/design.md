# Чтение переменных и графики без лишней материализации: design

## Context

Use the [shared target architecture](../../../../docs/target/device-variables.md)
for ownership, schemas, failure semantics and rollout. This document owns this
change's implementation decisions; its delta spec is the acceptance contract.

## Decisions
Telemetry GET /telemetry/devices/{id}/variables returns descriptors with stable
identity, source bindings, origin, unit, activation and capability states. It is
a projection of device definitions plus persisted telemetry, not an independent
editable registry. Archived identities remain discoverable for authorized history.

Existing bounded history/latest/chart/export interfaces accept an additive
variableId + source UUID selector. Legacy metric selector remains. Ambiguous
selectors fail rather than aliasing a human key to the wrong physical source.
Latest uses event time plus deterministic tie break; absence is not zero.

SQL filtering and aggregation stay in telemetry. Expose existing reducers and
bounded buckets, counts, gap evidence and formula-revision segments. Default
sample average does not imply time weighting; split revisions before averaging.
Dashboard-service only composes authorized scope/time/intent through typed ports.
No arbitrary expression/SQL input or shared database reads.

Query results may use bounded TTL cache keyed by authorization scope, source,
variable, revision policy, range and resolution. Freshness and permission checks
must precede delivery, including a cache hit. Chart aggregation does not persist
a new time series or write business state; rule input never uses decimated points.

## Delivery gate

Dependencies: materialize-device-variable-telemetry.
All tasks remain open until implemented with evidence. Update current contracts
only for delivered behavior. Do not archive dependent placeholders as complete.

