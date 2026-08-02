## Why

The current training lifecycle can mark runs as succeeded and register model
metadata without creating a real loadable model artifact. Python ML-service uses
local JSON artifacts and an in-memory registry, while analytics-service has the
deployment registry in PostgreSQL. This split prevents production inference from
loading the model selected by policy.

VoltCanary needs one coherent model artifact and runtime flow: train, persist,
hash, register, approve, load by `modelVersionId`, and infer.

## What Changes

- Add durable model artifact storage backed by MinIO/S3 or equivalent.
- Add a real ML worker/runtime that trains from feature artifacts.
- Serialize preprocessing and model artifacts.
- Register candidate models only after artifact creation and load-test
  inference succeed.
- Make `modelVersionId` the cross-service model identity.
- Replace production reliance on Python in-memory registry.
- Add job leases, heartbeats, cancellation checks, and stale-run handling.

## Capabilities

### New Capabilities

- `ml-artifact-runtime`: durable model artifacts, real training worker,
  registry/runtime identity, and model loading by version.

## Impact

- `ml-service/**`: dependencies, training worker, artifact store, model loading,
  inference routing, tests.
- `backend/services/analytics-service/**`: training orchestration, registry
  metadata, artifact verification, ML client contract.
- `deploy/**` and `docker-compose.yml`: worker runtime and artifact storage.
- `docs/api/**` and `docs/operations/**`: training/runtime procedures.

## Dependencies

Depends on `close-ml-dataset-lifecycle` and
`materialize-ml-feature-platform`.
