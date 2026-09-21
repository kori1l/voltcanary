# Add Capacity Verification Harness

## Why

VoltCanary's resume describes a calculated workload of 10,000 devices,
2.88 million events/day, 28.8 million numeric values/day and 864 million values
over 30 days. There is no reproducible capacity evidence suite in the repository.
Existing horizontal-ingestion smoke verifies correctness, not sustainable capacity.
The capacity claim requires ten numeric values per event, so the harness records
and validates the ten-metric contract explicitly before any long run.

## What Changes

- Add versioned workload profiles, API fixture provisioning, independent REST/MQTT
  generators, bounded orchestration and sanitized evidence reports.
- Add capability preflight that rejects unsupported metric profiles and records
  the implemented ten-metric contract before claiming the resume workload.
- Add complete logical-key reconciliation and sampled post-commit visibility
  measurement, including ambiguous request outcomes and retry accounting.
- Reuse existing meters and fill measured observability gaps: histograms,
  consumer-group lag, database/host resources and phase-aligned dashboards.
- Add resumable monthly-volume fixtures with disk estimation and separate
  ingestion, storage-only, scaling, HPA and recovery scenarios.
- Maintain the two project skills and the durable test procedure prepared with
  this proposal. No production throughput claim is established by this change's creation.

## Impact

New capability: `capacity-verification`.
Expected implementation areas: `scripts/load-tests/`, isolated deployment test
configuration, observability configuration, focused backend instrumentation if
needed, current API docs only if a metric contract is expanded.

No authentication bypass, production load run, resume edit, generic metric
registry, stateful HA deployment or ML throughput guarantee is introduced by
this proposal. Metric names/units/ranges are intentionally explicit; raising
max-metrics alone would not satisfy the contract.

## Current Delivery

This task delivers proposal/design/tasks/spec, project skills and
[the target test procedure](../../../docs/target/load-testing.md).
Harness implementation and measured capacity evidence remain pending.
