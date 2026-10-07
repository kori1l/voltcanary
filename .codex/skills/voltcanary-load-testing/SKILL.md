---
name: voltcanary-load-testing
description: Prepare and run VoltCanary telemetry load, soak, storage-volume, scaling, and recovery tests against explicit capacity claims. Use for load-test implementation or execution, not ordinary functional smoke tests.
---

# VoltCanary Load Testing

Use the repository-local [test plan](../../../docs/target/load-testing.md) as the
authoritative workload and evidence contract. Resolve paths from this skill.

## Preparation

1. Identify the requested mode: prepare tooling, execute a named profile, or
   diagnose a failed run. A request for a plan does not start a stress test.
2. For implementation, use `voltcanary-openspec` and the relevant backend/local
   operations skills. Inspect active changes; the initial proposal is
   `openspec/changes/archive/2026-10-05-add-capacity-verification-harness`.
   This change was archived by user request with open tasks; do not treat it as
   active or as proof that capacity gates passed. Use a new scoped change for
   outstanding live rerun/scaling/recovery work. Do not present proposed commands as installed.
3. Read the current ingestion and telemetry API contracts and inspect both metric
   catalogs. The ten-metric contract now exists in source; verify the deployed
   catalog as well. An unsupported profile is BLOCKED, never silently downscaled.
4. Record the target, commit, resources, image versions, replicas, actual Kafka
   partitions, shared Redis mode, rate limits, retention and enabled downstream
   services. Use a dedicated fixture cohort and an isolated test deployment.

## Execution invariants

- Provision devices, sources and credentials through supported APIs. Wait for
  every source ID and expected rotated-token prefix to be ready on every ingress
  replica before measurement; source presence alone does not prove auth readiness.
- Schedule 10,000 devices once per 300 seconds with deterministic phases. Distinguish
  device count, active MQTT connections, requests, readings and numeric values.
- Use an independent arrival schedule, report scheduled versus started operations,
  generator saturation and retries. Preserve reading identity and timestamp on retry.
- A `202` can mean duplicate or partial batch success. Inspect body/item outcomes.
  MQTT broker acknowledgement is not application acceptance or database persistence.
- Verify committed rows by `(device_id, source_id, recorded_at, metric_name)`.
  Do not equate insertion counters, handler timers, `created_at`, or HTTP success
  with transaction commit or full end-to-end completion.
- Keep high-cardinality identifiers in the local fixture manifest/report, not
  Prometheus labels. Keep raw tokens out of reports and tracked files.
- Separate capacity tests from rate-limit tests; record effective limits and
  generator IPs. Do not spoof trusted forwarding headers to bypass limits.
- Run the plan's short readiness gate before long tests. Use configured time,
  disk, queue and error stop conditions. Restore fault-injection targets on exit;
  never clean up unrelated datasets or use `docker compose down -v` as cleanup.
- `scripts/load-tests/run.ps1` is dry-run unless `-Execute` is supplied. With
  `-Execute`, `MaxMaterializedReadings=0` means the complete schedule; do not run
  a partial materialized schedule for a capacity claim.
- Prefer the user's local hourly profile: 3,600 measured seconds, 120,000 readings
  and 1.2M numeric values for resume-10. Report actual persisted throughput and
  label multiplication to a day as an extrapolation. Daily endurance and full
  monthly-volume trials are optional, not prerequisites for the hourly result.
- For local REST execution, keep automatic reconciliation enabled: the runner
  performs warmup outside measured totals, samples acceptance-to-visibility,
  waits through bounded drain, exports Compose TimescaleDB rows and reconciles
  them in bounded timestamp windows. Use `-SkipAutoReconcile` only when an
  external committed-row source is supplied and documented.
- Check the Docker data disk as well as the artifact disk; they may differ.
- A 24-hour test and a prefilled monthly-volume test answer different questions.
  Direct SQL storage fixtures cannot prove ingestion or downstream capacity.

## Delivery

Save a sanitized run manifest, generator results, observability snapshots and
reconciliation evidence under ignored `artifacts/load-tests/<run-id>/`.
Use `voltcanary-load-test-analysis` for claim-level verdicts. State which tests
actually ran and which remain planned; do not update capacity claims from estimates.
