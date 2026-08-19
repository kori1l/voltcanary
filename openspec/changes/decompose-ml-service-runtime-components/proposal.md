# Change Proposal: Decompose ML-Service Runtime Components

## Why

The Python ML-service has the same concentration problem on the runtime side:

- `training_service.py` is roughly 650 lines and combines request
  idempotency, anomaly training, failure training, model cards, approval gates,
  manifests, artifact persistence, hashing, timeout/cancellation checkpoints,
  and response assembly.
- `inference_service.py` is roughly 500 lines and combines baseline heuristic
  scoring, trained anomaly inference, failure inference, feature error
  responses, cache resolution, model loading, and response construction.
- `artifact_store.py` is roughly 430 lines and combines local storage, S3
  storage, health checks, cleanup preview/delete, URI parsing, bundle hashing,
  and boto3 client construction.
- Tests mutate the global `settings` singleton directly, which can hide
  order-dependent failures as the suite grows.

These modules are still testable, but every new ML runtime behavior increases
the blast radius.

## What Changes

- Split training, inference, artifact storage, and settings test support into
  focused components.
- Keep FastAPI routes and schema compatibility for `/train`, `/runs/{id}`,
  `/inference`, `/models`, and `/health/artifacts`.
- Preserve model artifact hash/manifest verification, serving cache behavior,
  idempotency semantics, approval gates, and failure-prediction output fields.
- Replace direct global settings mutation in tests with explicit fixtures or
  dependency injection.

## Non-Goals

- Do not introduce asynchronous workers or distributed queues in this change.
- Do not replace the current model algorithms.
- Do not change model artifact manifest formats unless required to preserve
  compatibility during extraction.
- Do not add new external ML dependencies.

## Impacted Areas

- `ml-service/app/services/training_service.py`
- `ml-service/app/services/inference_service.py`
- `ml-service/app/services/artifact_store.py`
- `ml-service/app/internal_boundary.py`
- `ml-service/app/api/*.py`
- `ml-service/app/tests/*.py`
- `openspec/specs/ml-artifact-runtime/spec.md`
- `openspec/specs/ml-failure-prediction/spec.md`

## Open Questions

- Should local and S3 artifact stores become separate provider classes behind a
  shared protocol immediately, or should the first pass extract S3 client
  construction and cleanup only?
- Should training service keep in-memory run state until a durable worker
  change is introduced, or should this refactor introduce a repository
  abstraction for future durability?
