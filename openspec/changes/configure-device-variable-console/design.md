# Интерфейс переменных и сквозная приёмка: design

## Context

Use the [shared target architecture](../../../docs/target/device-variables.md)
for ownership, schemas, failure semantics and rollout. This document owns this
change's implementation decisions; its delta spec is the acceptance contract.

## Workflow
Under device type variables, create a definition, choose observed field aliases
and units, then choose a preset (scale, offset, unit conversion) or bounded
arithmetic expression. Default simple controls show 235 → 23.5 → 22.7 °C.
Display adapter decoding separately to avoid applying Modbus scale twice.

Preview is explicit and side-effect free; edits to formula, inputs, units or
source mark preview stale and block publication until refreshed. Guard racing
responses. Publish revision and activation/rollout are distinct actions with
scope review and per-source progress. Existing bindings never silently follow
the newest revision.

Show UNBOUND/PROPAGATING/ACTIVE/ERROR/DISABLED from desired versus applied
generation/evidence, not from HTTP save success. Per-source evaluation errors
are inspectable without hiding valid siblings. Two devices of a type share a
definition while showing independent values and bindings.

## Integration
Telemetry/dashboard/rule selectors use server descriptors with origin, source
and unit. Archived entries remain available for historical views. Missing data
is not zero. The variable-rule editor supports only server-declared conditions.
Do not append implementation to large route pages; use focused components,
hooks, domain API modules and existing dense-console conventions.

## Acceptance and operations
Run the declared two-device fixture through real broker/persistence/rules,
including revision edits, delayed events, two replicas, restart and rollback.
Measure baseline versus configured-message latency/output amplification using a
fixed fixture; report measurements and limits without inventing capacity claims.
Document feature flags, bundle reconciliation, parked events, restore/replay,
retention dependencies and observability in current docs only after implementation.

## Delivery gate

Dependencies: define-device-type-variables, propagate-variable-processing-contracts, materialize-device-variable-telemetry, query-device-variable-series, alert-on-device-variables.
All tasks remain open until implemented with evidence. Update current contracts
only for delivered behavior. Do not archive dependent placeholders as complete.
