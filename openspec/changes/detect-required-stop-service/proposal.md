# Change: Detect possible missed required stop service with durable evidence

**SUPERSEDED AS IMPLEMENTATION SCOPE (2026-11-03):** use the
[configurable operations program](../README.md#configurable-operations-program)
and [generic architecture](../../../docs/target/configurable-operations-platform.md).
Retain this proposal only as scenario constraints/fixtures. Do not implement a
specialized transit core from it. User stopped implementation; it remains stopped.

Status: proposed 2026-11-03; planning only. No runtime, deployment, provider-setting change or paid/live trial is authorized by creating this proposal.

## Why

A spatial exit and lack of a currently open door are insufficient to establish a missed required service. The platform needs temporal visits, explicit obligations, coverage and correction semantics.

## What Changes

- Add typed REQUIRED_STOP_SERVICE rule preparation/activation in alert-service and a deterministic durable visit evaluator in analytics-service.
- Use actual trip occurrence, pinned zones/policy, observed entry/exit and a bounded service witness window.
- Produce evidence-backed possible-miss outcomes, UNKNOWN/EXEMPT states, corrections and deduplicated alert lifecycle through existing notification ports.

## Dependencies

T1 add-transit-reference-catalog, T2 preserve-vehicle-operational-events and T3 add-reusable-spatial-zones owner contracts. No LLM prerequisite.

## Design and Impact

[Authoritative target architecture](../../../docs/target/transit-stop-monitoring.md), [acceptance](../../../docs/target/transit-stop-monitoring-acceptance.md), [program](../README.md#transit-stop-monitoring-program). See design.md for implementation boundaries and specs/required-stop-service/spec.md for required behavior. Current APIs remain compatible until an explicit implementation changes them.

## Out of Scope

No invented live telemetry, assumed mandatory stops, unrestricted scripts/connectors, automatic publication, hidden model escalation, second assistant/ledger, paid model trials or new infrastructure framework. Existing failed A5/A6 evidence and release gates remain open.

