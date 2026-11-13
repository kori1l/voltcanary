# Change: Reusable map features and table-linked map selection

Status: proposed 2026-11-03 after the user corrected the product scope. Planning only; runtime implementation remains stopped until explicitly resumed. This is a generic platform capability, not a specialized transit workflow.

## Why

The platform must compose many company-specific scenarios from typed data, relations, observations, geometry and rules. A separate controller/evaluator branch for every business scenario would defeat that goal.

## What Changes

Add generic points, circles and capability-gated bounded polygons/feature sets; provide click/search/draw/import and shared map/table selection with stable IDs. Features represent any place, not just stops.

## Dependencies

G1 references for collection links; geometry authoring can begin independently. Preserve current owner/device circles.

## Design and Impact

Complete contracts now include VCL types/methods, efficient dense native storage,
customer DB adapters and strict imports; see the linked normative documents in
design.md. G7/UI/acceptance participate in each vertical slice, not only at the end.

[Configurable platform architecture](../../../docs/target/configurable-operations-platform.md) and [authoritative program](../README.md#configurable-operations-program). Reuse current services, variables, operational schedules/facts, dashboards and assistant orchestration. Existing APIs stay compatible; implementation requires owning migrations/contracts and focused tests.

## Out of Scope

No paid/live campaigns, arbitrary executable SQL/scripts, silent source/identity substitution, duplicate assistant/ledger, automatic publication or legal-policy assumptions. Existing failed A5/A6 gates remain open.

