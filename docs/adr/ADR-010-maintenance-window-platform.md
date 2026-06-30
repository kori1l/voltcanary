# ADR-010: Maintenance Window Platform

## Status

Accepted

## Context

Operators need to schedule planned maintenance for devices. During planned
work, telemetry gaps and abnormal readings should not pollute analytics,
machine learning, or alert workflows. The platform must keep telemetry
immutable while giving downstream services enough context to interpret the data
correctly.

## Decision

Device-service owns maintenance windows and exposes the user-facing API.
Maintenance changes are published as `maintenance-window.changed.v1` events
with full snapshots and monotonic versions.

Analytics-service and alert-service maintain independent local read models.
They do not call device-service synchronously in telemetry, recalculation, or
alert-creation hot paths. Each service also runs reconciliation against
device-service internal change APIs to recover from missed events or projection
lag.

Telemetry remains immutable. Maintenance policy is applied at interpretation
time:

- analytics excludes matching samples from derived calculations;
- ml-service receives explicit excluded intervals;
- alert-service suppresses matching new alerts and auto-resolves suppressible
  existing alerts.

## Consequences

This design has more moving parts than a synchronous lookup from each
consumer. It requires local read models, version checks, reconciliation jobs,
projection metrics, and audit records.

The tradeoff is better service independence and scalability. Analytics and
alert-service can scale horizontally without adding synchronous pressure to
device-service. Replays and backdated maintenance changes are handled through
recalculation and auto-resolution behavior rather than mutating raw telemetry.
