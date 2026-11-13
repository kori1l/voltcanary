# Change: Add reusable account-owned spatial zones and revisioned zone sets

**SUPERSEDED AS IMPLEMENTATION SCOPE (2026-11-03):** use the
[configurable operations program](../README.md#configurable-operations-program)
and [generic architecture](../../../docs/target/configurable-operations-platform.md).
Retain this proposal only as scenario constraints/fixtures. Do not implement a
specialized transit core from it. User stopped implementation; it remains stopped.

Status: proposed 2026-11-03; planning only. No runtime, deployment, provider-setting change or paid/live trial is authorized by creating this proposal.

## Why

Current owner/device circles support history visualization, but they are not a shared revisioned domain resource for server rules, multiple vehicles and reviewed AI actions.

## What Changes

- Add account-owned versioned circle zones, reusable zone sets, permission checks and concurrency/idempotency contracts.
- Provide one canonical geometry contract and explicit legacy import while retaining the existing monitoring endpoint.
- Allow optional platform-stop occurrence provenance without merging nearby stops or rewriting active rules.

## Dependencies

Independent of T1/T2 for manual circles; transit snapshot association consumes T1 when available.

## Design and Impact

[Authoritative target architecture](../../../docs/target/transit-stop-monitoring.md), [acceptance](../../../docs/target/transit-stop-monitoring-acceptance.md), [program](../README.md#transit-stop-monitoring-program). See design.md for implementation boundaries and specs/reusable-spatial-zones/spec.md for required behavior. Current APIs remain compatible until an explicit implementation changes them.

## Out of Scope

No invented live telemetry, assumed mandatory stops, unrestricted scripts/connectors, automatic publication, hidden model escalation, second assistant/ledger, paid model trials or new infrastructure framework. Existing failed A5/A6 evidence and release gates remain open.

