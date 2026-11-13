# Вычисление и хранение переменных в телеметрии: design

## Context

Use the [shared target architecture](../../../../docs/target/device-variables.md)
for ownership, schemas, failure semantics and rollout. This document owns this
change's implementation decisions; its delta spec is the acceptance contract.

## Decisions
Telemetry owns the evaluator, preview, measurements and lineage. No evaluator in
ingestion, dashboard, analytics or common-events. The pure domain evaluator uses
pinned immutable definitions and same-message observed inputs, with explicit
finite arithmetic and unit validation. AST and defensive budgets are specified
in target architecture section 6.

Extend the existing measurement storage/read abstraction: observed and computed
numeric variables have stable variableId and reserved internal token; built-ins
retain legacy names. Provenance may be a companion table, but history/latest/export
must use the same query path. Reconcile old timestamp uniqueness with new
event/variable/revision identity before applying migrations.

Persist valid siblings despite per-variable data failures; record failed outcomes
without numeric placeholders. Persist outputs, lineage, failure rows, inbox marker
and outbox in one transaction. Infrastructure errors roll back. Source input
evidence must remain explainable throughout output retention.

## Events and compatibility
Publish telemetry.measurements.accepted.v1 with deviceId key and bounded typed
observed/derived rows, source UUID, variable/revision/unit/quality/lineage and
LIVE|BACKFILL eligibility. Built-in validated v1 continues unchanged for existing
jobs. No computed data is fed back into raw; no implicit ML/energy participation.

## Preview
POST /telemetry/variables/preview authorizes definition/source scope and evaluates
a bounded draft against explicit inputs. It returns intermediate values,
canonical expression digest and diagnostics, writes no measurements/events and
uses identical evaluator/unit rules to production.

## Delivery gate

Dependencies: define-device-type-variables, propagate-variable-processing-contracts.
All tasks remain open until implemented with evidence. Update current contracts
only for delivered behavior. Do not archive dependent placeholders as complete.

