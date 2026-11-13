# Change: Bounded declarative derivations and temporal alert rules

Status: implementation resumed in the configurable program. Rule reservations, reviewed source/native-field grants with local epoch revocation and reviewed private-reference evidence-policy metadata are implemented G0 prerequisites. Actual evidence storage/retention execution, atomic alert/effect admission, temporal runtime, readiness and activation remain pending. This is a generic platform capability, not a specialized transit workflow.

## Why

The platform must compose many company-specific scenarios from typed data, relations, observations, geometry and rules. A separate controller/evaluator branch for every business scenario would defeat that goal.

## What Changes

Add a versioned typed graph for authorized lookups, spatial/session/interval/window operators, aggregates, conditions and alert outputs. Ship generic operators, not business-name branches. Keep expression, query and temporal runtime responsibilities explicit.

## Dependencies

G1 typed data/ref contracts, G3 observable quality; G2 when spatial operators are used.

## Design and Impact

Complete contracts now include VCL types/methods, efficient dense native storage,
customer DB adapters and strict imports; see the linked normative documents in
design.md. G7/UI/acceptance participate in each vertical slice, not only at the end.

[Configurable platform architecture](../../../docs/target/configurable-operations-platform.md) and [authoritative program](../README.md#configurable-operations-program). Reuse current services, variables, operational schedules/facts, dashboards and assistant orchestration. Existing APIs stay compatible; implementation requires owning migrations/contracts and focused tests.

## Out of Scope

No paid/live campaigns, arbitrary executable SQL/scripts, silent source/identity substitution, duplicate assistant/ledger, automatic publication or legal-policy assumptions. Existing failed A5/A6 gates remain open.


## Bus expressibility/readiness audit

The [bus readiness contract](../../../docs/target/configurable-operations-bus-readiness.md) adds BUS01-BUS10 capture, occurrence routing, exact door predicate and exit/non-arrival gates to this pending work. It preserves generic core ownership, required-stops-only user policy and all existing frozen fixtures/quality gates; it is not evidence of implementation.
