# Change: Manual table, map, rule and evidence workbench

Status: implementation resumed 2026-11-03 through the configurable program. The
first native table/row UI increment consumes published G1 owner APIs. Complete
G1–G4 capabilities and full workbench acceptance remain pending. This is a generic
platform capability, not a specialized transit workflow.

## Why

The platform must compose many company-specific scenarios from typed data, relations, observations, geometry and rules. A separate controller/evaluator branch for every business scenario would defeat that goal.

## What Changes

Provide ordinary UI for collections/rows/relations, shared map objects, rule building, dry-run, version diffs and activation. Extend dashboards through existing owner manifests and renderer.

## Dependencies

G1–G4 published owner contracts; G5 templates optional.

## Design and Impact

Complete contracts now include VCL types/methods, efficient dense native storage,
customer DB adapters and strict imports; see the linked normative documents in
design.md. G7/UI/acceptance participate in each vertical slice, not only at the end.

[Configurable platform architecture](../../../docs/target/configurable-operations-platform.md) and [authoritative program](../README.md#configurable-operations-program). Reuse current services, variables, operational schedules/facts, dashboards and assistant orchestration. Existing APIs stay compatible; implementation requires owning migrations/contracts and focused tests.

## Out of Scope

No paid/live campaigns, arbitrary executable SQL/scripts, silent source/identity substitution, duplicate assistant/ledger, automatic publication or legal-policy assumptions. Existing failed A5/A6 gates remain open.

