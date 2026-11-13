# Change: Add manual transit monitoring setup and evidence dashboards

**SUPERSEDED AS IMPLEMENTATION SCOPE (2026-11-03):** use the
[configurable operations program](../README.md#configurable-operations-program)
and [generic architecture](../../../docs/target/configurable-operations-platform.md).
Retain this proposal only as scenario constraints/fixtures. Do not implement a
specialized transit core from it. User stopped implementation; it remains stopped.

Status: proposed 2026-11-03; planning only. No runtime, deployment, provider-setting change or paid/live trial is authorized by creating this proposal.

## Why

The scenario must be a platform capability with an inspectable manual workflow, not an AI-only script. Current circles do not expose required-stop policies or server visit outcomes.

## What Changes

- Add manual trip/mandatory-stop/zone/rule review and activation workflows using owning APIs.
- Expose route-stop-zone layers and visit/coverage evidence through registered dashboard capabilities.
- Preserve truthful current/empty/stale states and ordinary private-draft/review equality.

## Dependencies

T1–T4 owner contracts. Reuse existing dashboard composition, map/boolean history, renderer, draft receipts and notification settings UI.

## Design and Impact

[Authoritative target architecture](../../../docs/target/transit-stop-monitoring.md), [acceptance](../../../docs/target/transit-stop-monitoring-acceptance.md), [program](../README.md#transit-stop-monitoring-program). See design.md for implementation boundaries and specs/transit-monitoring-workspace/spec.md for required behavior. Current APIs remain compatible until an explicit implementation changes them.

## Out of Scope

No invented live telemetry, assumed mandatory stops, unrestricted scripts/connectors, automatic publication, hidden model escalation, second assistant/ledger, paid model trials or new infrastructure framework. Existing failed A5/A6 evidence and release gates remain open.

