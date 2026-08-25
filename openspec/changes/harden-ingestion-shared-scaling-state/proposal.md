# Change Proposal: Harden Ingestion Shared Scaling State

## Why

Push ingress can scale horizontally only if correctness state is shared across
replicas. Current local fallbacks are useful for development, but production-like
multi-replica behavior requires explicit Redis-backed idempotency and rate
limits, deterministic Kafka keys, and clear degraded behavior when dependencies
are unavailable.

This change implements phase 5 from
`docs/voltcanary_target_ingestion_architecture.md`.

## What Changes

- Make idempotency and rate limiting explicitly shared across ingestion replicas
  for multi-replica mode.
- Remove silent in-memory correctness fallback from production-like settings.
- Document and test Redis, Kafka, and source-config dependency failure behavior.
- Keep Kafka publication to `telemetry.raw.v1` deterministic by documented key.

## Non-Goals

- Do not add active connector ownership in this change.
- Do not add new protocols.
- Do not preserve local fallback semantics in modes that claim multi-replica
  correctness.

## Impacted Areas

- `backend/services/ingestion-service`
- `deploy/` and local runtime configuration if scaling profiles are exposed
- `docs/api/ingestion-api.md`
- `docs/events/event-envelope.md`
- `openspec/specs/ingestion-scaling-runtime/spec.md`
