# Change: Configurable normalized observations and capture quality

Status: implementation resumed in the configurable program. The source schema journal/publication, opt-in durable Boolean/TEXT accepted-input journal and actual input generations/cutover are G0 prerequisite increments. Reviewed mappings/capture controls, exact numeric conversions, qualified upstream coverage and temporal readiness qualification remain pending. This is a generic platform capability, not a specialized transit workflow.

## Why

The platform must compose many company-specific scenarios from typed data, relations, observations, geometry and rules. A separate controller/evaluator branch for every business scenario would defeat that goal.

## What Changes

Expose source capabilities and typed mapping of position, boolean/state transitions and operational intervals. Add capture policies and durable event delivery so latest-only views cannot masquerade as complete input to absence/window rules.

## Dependencies

Reuse existing source security, ingestion, telemetry and numeric processing bundles. G1 defines optional entity/field bindings.

## Design and Impact

Complete contracts now include VCL types/methods, efficient dense native storage,
customer DB adapters and strict imports; see the linked normative documents in
design.md. G7/UI/acceptance participate in each vertical slice, not only at the end.

[Configurable platform architecture](../../../docs/target/configurable-operations-platform.md) and [authoritative program](../README.md#configurable-operations-program). Reuse current services, variables, operational schedules/facts, dashboards and assistant orchestration. Existing APIs stay compatible; implementation requires owning migrations/contracts and focused tests.

## Out of Scope

No paid/live campaigns, arbitrary executable SQL/scripts, silent source/identity substitution, duplicate assistant/ledger, automatic publication or legal-policy assumptions. Existing failed A5/A6 gates remain open.


## Bus expressibility/readiness audit

The [bus readiness contract](../../../docs/target/configurable-operations-bus-readiness.md) adds BUS01-BUS10 capture, occurrence routing, exact door predicate and exit/non-arrival gates to this pending work. It preserves generic core ownership, required-stops-only user policy and all existing frozen fixtures/quality gates; it is not evidence of implementation.
