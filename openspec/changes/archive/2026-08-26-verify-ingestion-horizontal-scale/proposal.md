# Change Proposal: Verify Ingestion Horizontal Scale

## Why

After push ingress, source runtime config, shared correctness state, connector
runtime, and initial active adapters are in place, the architecture needs a
deployment-scale verification gate. The gate proves that stateless ingress can
hit any replica and active sources still have exactly one owner.

This change implements phase 10 from
`docs/voltcanary_target_ingestion_architecture.md`.

## What Changes

- Add multi-replica local or CI smoke verification for ingestion-service.
- Verify push ingress, source config replication, shared idempotency/rate
  limits, connector lease ownership, failover, and deterministic Kafka keys.
- Add deployment/operations documentation for running ingestion horizontally.
- Treat failures in this gate as architecture failures, not test flakiness to
  bypass.

## Non-Goals

- Do not introduce a multi-region production deployment in this change.
- Do not split ingestion into separate HTTP/MQTT/industrial services yet.
- Do not keep single-replica-only correctness assumptions after this gate.

## Impacted Areas

- `deploy/`
- `scripts/`
- `backend/services/ingestion-service`
- `docs/operations/` or deployment docs
- `openspec/specs/ingestion-scaling-runtime/spec.md`
