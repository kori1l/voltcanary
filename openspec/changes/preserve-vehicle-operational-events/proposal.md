# Change: Preserve vehicle operational events and observation coverage

**SUPERSEDED AS IMPLEMENTATION SCOPE (2026-11-03):** use the
[configurable operations program](../README.md#configurable-operations-program)
and [generic architecture](../../../docs/target/configurable-operations-platform.md).
Retain this proposal only as scenario constraints/fixtures. Do not implement a
specialized transit core from it. User stopped implementation; it remains stopped.

Status: proposed 2026-11-03; planning only. No runtime, deployment, provider-setting change or paid/live trial is authorized by creating this proposal.

## Why

Latest-per-vehicle VP coalescing can lose brief door openings and drops door/stop events without GPS. It cannot support an honest negative service observation.

## What Changes

- Keep the current latest-map path and add bounded durable capture for allowlisted vehicle position, door, stop and journey events.
- Add typed operational ingestion, durable ACK/outbox/inbox, event identity and authorized evidence storage.
- Record capture gaps, reconnects, overflows and unknown completeness instead of treating missing doors as closed.

## Dependencies

Independent of T1/T3. Reuse existing HSL source bindings, ingestion authentication and telemetry infrastructure.

## Design and Impact

[Authoritative target architecture](../../../docs/target/transit-stop-monitoring.md), [acceptance](../../../docs/target/transit-stop-monitoring-acceptance.md), [program](../README.md#transit-stop-monitoring-program). See design.md for implementation boundaries and specs/vehicle-operational-events/spec.md for required behavior. Current APIs remain compatible until an explicit implementation changes them.

## Out of Scope

No invented live telemetry, assumed mandatory stops, unrestricted scripts/connectors, automatic publication, hidden model escalation, second assistant/ledger, paid model trials or new infrastructure framework. Existing failed A5/A6 evidence and release gates remain open.

