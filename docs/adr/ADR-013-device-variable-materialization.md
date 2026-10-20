# ADR-013: Materialize device variables in telemetry-service

Date: 2026-10-16

Status: Accepted; release verification remains tracked in OpenSpec.

## Context

An account needs to declare a numeric variable for a device type, transform a
retained input (for example 235 to 22.7 °C), store it alongside existing readings
and use the same identity in graphs and thresholds. Formula changes and delayed
events must not change the meaning of already accepted data. Persisting every
dashboard aggregation would multiply storage without creating useful new facts.

## Decision

Device-service owns definitions, immutable revisions and explicit source
bindings. It publishes immutable versioned processing bundles through its outbox.
Ingestion stamps the trusted bundle reference. Telemetry-service evaluates bounded
numeric ASTs, exposes the identical evaluator for preview and transactionally
stores measurements, lineage, receipt and accepted-event outbox.

Analytics evaluates variable thresholds from accepted full-resolution values;
alert-service owns alert lifecycle. Dashboard reductions execute on reads and
do not create stored variables. Arbitrary scripts, cross-device joins and
windowed stream computations are outside this evaluator.

## Consequences

No additional deployable service or AWS invocation is needed. Existing storage,
authorization and alert flows remain reusable. Formula revisions are reproducible,
and bounded work limits prevent user expressions from becoming unbounded jobs.
Costs grow with materialized outputs, lineage and retained input snapshots.
Projection recovery, pending events, retention and capacity need explicit
operation and testing. A future isolated execution service requires a separate
decision driven by stateful or computational requirements and measurements.

See [current architecture](../current/architecture/device-variables.md) and the
[six-change program](../../openspec/changes/README.md).
