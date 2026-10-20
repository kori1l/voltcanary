# Пороговые тревоги по переменным устройств: design

## Context

Use the [shared target architecture](../../../docs/target/device-variables.md)
for ownership, schemas, failure semantics and rollout. This document owns this
change's implementation decisions; its delta spec is the acceptance contract.

## Decisions
Alert-service remains owner of rule definitions and versions. Extend existing
rules with stable variableId and explicit dataSourceId, threshold/operator and
unit contract; scope must not mix multiple sources automatically. V1 conditions
are ABOVE_THRESHOLD and BELOW_THRESHOLD, not duration or arbitrary code.

Analytics adds a focused consumer path for telemetry.measurements.accepted.v1.
It evaluates exact eligible numeric observations with rule snapshots through
the existing internal rule boundary and bounded cache. Dependency failures
retry or enter diagnosed unavailable state, never succeed as an empty rule list.
It does not enqueue duplicate legacy recalculation/ML jobs from the new topic.

Persist evaluation identity by measurement + rule version, evidence and outbox
atomically. Publish existing anomaly.detected.v1 with optional structured
variable/source/revision/value/unit evidence after tolerant readers are deployed.
Alert lifecycle consumes that evidence and preserves rule/source-aware cooldown,
deduplication and durable notification requests. No direct raw-telemetry consumer
is added to alert-service and no variable-only notification provider is added.

## Quality, maintenance and revisions
Only LIVE, valid, authorized eligible evidence matches. BACKFILL cannot create
live alerts. Existing maintenance exclusions and alert suppression retain their
owners. Disabled/unbound/missing values are unavailable, not zero.
Rule maxLiveAgeSeconds defaults to 600 within a bounded configurable server
policy. Older observations are recorded as stale without a current match;
retries preserve recordedAt rather than resetting freshness to processing time.
New formula revision keeps variable identity/unit and appears in evidence.
V1 preserves existing manual alert resolution, without invented recovery events.

Dry run uses the same threshold predicate over bounded full-resolution authorized
measurements with diagnostics; chart downsampling must not hide a short spike.
Full source/rule/version identity travels through evidence and dedup keys.

## Delivery gate

Dependencies: materialize-device-variable-telemetry, query-device-variable-series.
All tasks remain open until implemented with evidence. Update current contracts
only for delivered behavior. Do not archive dependent placeholders as complete.
