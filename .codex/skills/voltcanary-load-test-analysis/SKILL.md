---
name: voltcanary-load-test-analysis
description: Analyze VoltCanary load-test evidence, identify bottlenecks, and decide which device, daily-volume, monthly-storage, scaling, and recovery claims are supported. Use when reviewing performance results or preparing evidence-backed capacity statements.
---

# VoltCanary Load Test Analysis

Read [the test plan and report contract](../../../docs/target/load-testing.md).
Analyze existing artifacts before proposing another expensive run.

## Evidence checks

- Match commit, deployment, workload, duration and metric catalog to the claim.
  Five metrics at the resume cadence produce 14.4M values/day, not 28.8M.
- For the user's local hourly run, distinguish 120,000 measured readings and
  1.2M measured values from the 24x daily projection. Project from reconciled
  observed totals and actual measurement duration, never from target arithmetic.
  Daily endurance and 864M populated storage remain NOT_RUN unless tested;
  that does not invalidate an otherwise sound hourly result.
- Require actual offered/started/accepted/persisted volumes. Separate unique
  readings from attempts, retries, duplicates, intentional rejects and ambiguous
  timeout outcomes. Inspect missing and unexpected logical keys after drain.
- Report p95/p99 per endpoint and phase with sample counts. Do not average
  percentiles across replicas or derive quantiles from a timer sum/count.
- Treat polling-based persistence detection as a sampled upper bound with the
  polling interval and timeout censoring disclosed. Application timers and row
  timestamps can precede commit. Device occurrence time includes delivery delay.
- Check generator saturation before attributing dropped iterations to the server.
  Check rate limits before calling rejected traffic a capacity bottleneck.
- Separate dashboard availability, ingestion, persistence, analytics, alert and
  ML evidence. A green ingest request does not verify every service in the platform.
- Relate CPU/GC/connection waits/disk to lag and oldest pending outbox age.
  Preserve raw evidence; make one controlled change before a comparable rerun.
- Compare scaling at fixed resources per replica and fixed shared dependencies,
  then disclose total added resources, partitions, HPA behavior and bottlenecks.
  Additional pods alone do not prove increased sustainable throughput.
- Require populated indexed storage evidence for 864M values. Extrapolation from
  a small sample is an estimate; a restart is not evidence of stateful failover.

## Verdicts

Use PASS, FAIL, BLOCKED, INCONCLUSIVE or NOT_RUN per claim, with evidence paths
and reasons. A missing histogram, failed generator, unresolved timeout or
incomplete reconciliation cannot become PASS. Distinguish proposed thresholds
from requirements in the source document and freeze them before the measured run.

Return supported claims, failed/unknown claims, the first evidenced bottleneck,
and the smallest next experiment. Only propose a resume statement with measured
rate, duration, hardware, workload and relevant latency; never convert a calculated
monthly volume into a measured month of operation.
