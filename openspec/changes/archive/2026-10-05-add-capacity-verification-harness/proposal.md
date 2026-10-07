# Add Capacity Verification Harness

## Archive disposition — 2026-10-05

Archived at the user's explicit request as an incomplete delivery snapshot.
This archival does not mean all tasks passed or that capacity claims are proven.
The 19 completed tasks and four open tasks are retained without changing their
checkboxes. Token-aware full-cohort live rerun, fixed-replica/HPA comparisons,
recovery trials and their required verification remain outstanding as described
in tasks.md and review.md. Optional daily/monthly/scaling/recovery/MQTT claims
retain their recorded NOT_RUN or unverified status; the strict hourly claim has
not been promoted to PASS.

Archive with --skip-specs so this incomplete change does not promote its delta
to the main capacity-verification specification. Runnable code and current
operations guidance remain in the repository; resuming outstanding work requires
a new explicitly scoped change linked to this archive.

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
- Keep monthly-volume, scaling, HPA and recovery work as explicitly optional
  extensions whose absence cannot be reported as measured evidence.
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

The executable local harness now covers deterministic REST/MQTT generation,
API fixture provisioning, a real warmup followed by a measured window,
in-run committed-visibility sampling, continuous disk/error/lag guards,
automatic local TimescaleDB export and bounded logical reconciliation.

The selected proof is a short `current-5` integration smoke followed by one
measured hour of `resume-10`. A daily figure is calculated from reconciled
hourly evidence and is labelled an extrapolation. A 24-hour endurance run and
an 864-million-value storage population are explicitly outside the selected
local scope and remain `NOT_RUN`.
