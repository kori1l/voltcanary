# Change: Add versioned transit reference and required-stop policies

**SUPERSEDED AS IMPLEMENTATION SCOPE (2026-11-03):** use the
[configurable operations program](../README.md#configurable-operations-program)
and [generic architecture](../../../docs/target/configurable-operations-platform.md).
Retain this proposal only as scenario constraints/fixtures. Do not implement a
specialized transit core from it. User stopped implementation; it remains stopped.

Status: proposed 2026-11-03; planning only. No runtime, deployment, provider-setting change or paid/live trial is authorized by creating this proposal.

## Why

The platform has live vehicle identities but no authoritative ordered trip/stop snapshot or explicit required-service policy. A route label or nearby map point cannot establish the stop a bus is expected to serve.

## What Changes

- Add provider adapters for validated GTFS packages and optional Digitransit lookup, immutable effective snapshots and bounded account-authorized reads.
- Resolve HFP identity to an actual trip instance without guessing; retain provider namespace, platform and repeated stop occurrence.
- Add explicit versioned required-stop policies with provenance. GTFS membership or pickup permissions alone never imply mandatory door opening.

## Dependencies

No new-program prerequisite. Reuse device/source ownership, signed account access and the existing fixed HSL integration.

## Design and Impact

[Authoritative target architecture](../../../docs/target/transit-stop-monitoring.md), [acceptance](../../../docs/target/transit-stop-monitoring-acceptance.md), [program](../README.md#transit-stop-monitoring-program). See design.md for implementation boundaries and specs/transit-reference-catalog/spec.md for required behavior. Current APIs remain compatible until an explicit implementation changes them.

## Out of Scope

No invented live telemetry, assumed mandatory stops, unrestricted scripts/connectors, automatic publication, hidden model escalation, second assistant/ledger, paid model trials or new infrastructure framework. Existing failed A5/A6 evidence and release gates remain open.

