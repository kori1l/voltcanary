# Change: Orchestrate reviewed transit monitoring with bounded research and model routing

**SUPERSEDED AS IMPLEMENTATION SCOPE (2026-11-03):** use the
[configurable operations program](../README.md#configurable-operations-program)
and [generic architecture](../../../docs/target/configurable-operations-platform.md).
Retain this proposal only as scenario constraints/fixtures. Do not implement a
specialized transit core from it. User stopped implementation; it remains stopped.

Status: proposed 2026-11-03; planning only. No runtime, deployment, provider-setting change or paid/live trial is authorized by creating this proposal.

## Why

Users should request a bus monitoring workflow in natural language, resolve real resources and missing policy, review actions and resume partial work. Unbounded agents calling each other or per-point LLM decisions would weaken correctness and cost control.

## What Changes

- Register typed transit resolution, zone/policy/rule prepare/test/activate skills in the existing assistant and dependency/receipt state machine.
- Introduce bounded read-only research jobs and coordinator-owned worker questions/results without recursive autonomy.
- Extend existing policy-driven routing to opt-in qualified skill/phase profiles, durable model bindings and one shared attempt/cost ledger.
- Preserve confirmation, provenance, exact resource identity, accepted reviews and replay without generation availability.

## Dependencies

T1–T4 typed owner contracts; T5 capabilities for requested dashboards. Reuse implemented A1/A2/A3/A4 runtime and owning A5/V/R requirements. No requirement to archive A5/A6 before designing this explicit scope; release gates remain open.

## Design and Impact

[Authoritative target architecture](../../../docs/target/transit-stop-monitoring.md), [acceptance](../../../docs/target/transit-stop-monitoring-acceptance.md), [program](../README.md#transit-stop-monitoring-program). See design.md for implementation boundaries and specs/assistant-transit-monitoring/spec.md for required behavior. Current APIs remain compatible until an explicit implementation changes them.

## Out of Scope

No invented live telemetry, assumed mandatory stops, unrestricted scripts/connectors, automatic publication, hidden model escalation, second assistant/ledger, paid model trials or new infrastructure framework. Existing failed A5/A6 evidence and release gates remain open.

