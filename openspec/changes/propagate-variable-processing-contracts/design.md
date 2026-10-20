# Версионированные контракты обработки источников: design

## Context

Use the [shared target architecture](../../../docs/target/device-variables.md)
for ownership, schemas, failure semantics and rollout. This document owns this
change's implementation decisions; its delta spec is the acceptance contract.

## Decisions
Keep source.runtime-config.v1 for source authorization and credentials.
Publish a separate secret-free source.processing-config.v1 snapshot keyed by
dataSourceId. A source runtime record references the required processing
generation/digest; ingestion must have compatible auth/input snapshots.

Pin immutable processingContext at server acceptance and carry it outside metrics
in raw events. Choose acceptance version, not event-time version. Preserve
sourceEventId and context on retries. Dedup input identity must not change solely
because a formula changed; clients cannot set trusted context or computed output.

Each ingestion replica needs complete source coverage. Telemetry uses a durable
projection with historical immutable bundles and authenticated reconciliation.
Compaction retains latest only, so device retains historical generations for
delayed input/replay. Monotonic generation/digest checks prevent regression and
detect same-generation conflicts.

## Validation and delivery
Configured numeric inputs are validated by declared input contracts; legacy
built-ins keep physical bounds. required raw=235 is never relabelled temperatureC.
Do not retain undeclared inputs or secrets. Missing/incompatible snapshots block
configured acceptance with retryable 503, while legacy sources remain unchanged.

Telemetry parks accepted events with unknown pinned bundle in a durable bounded
inbox, retries reconciliation, and eventually DLQs with identity/reason. It does
not commit an irrevocable processed marker before the event becomes recoverable.

## Compatibility and rollout
Add optional processingContext to raw DTO with tolerant reader fixtures before
producer activation. Raw topic remains telemetry.raw.v1 with deviceId key.
If actual deployed deserializers cannot accept an additive field, implement a
versioned transition before enabling sources. Never change v1 meaning silently.

## Delivery gate

Dependencies: define-device-type-variables.
All tasks remain open until implemented with evidence. Update current contracts
only for delivered behavior. Do not archive dependent placeholders as complete.
