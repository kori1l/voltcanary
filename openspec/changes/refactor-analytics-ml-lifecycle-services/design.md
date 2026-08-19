# Design: Refactor Analytics ML Lifecycle Services

## Current State

The analytics ML implementation is functionally rich but concentrated in a few
classes. The largest risk is not line count alone; the risky pattern is many
state transitions and adapter interactions in one application service.

Observed boundary mixing:

- Dataset preview and creation logic share validation, telemetry reads,
  maintenance read-model reads, label readiness, hashing, candidate selection,
  timeline event rendering, canonical row materialization, and lifecycle
  delete/archive checks inside one service.
- Feature generation mixes source-specific feature builders with feature
  snapshot lifecycle management and DTO assembly.
- Model registry mixes model lifecycle, deployment policy resolution, audit,
  demo/system seed handling, and artifact gate validation.
- Analytics recalculation mixes orchestration with calculators, ML client
  fallback, outbox emission, persistence, hashes, and UI-facing explanations.

## Target Decomposition

Keep the existing API surface and split by use case:

- Dataset:
  - `MlDatasetPreviewService`
  - `MlDatasetValidationService`
  - `MlDatasetMaterializationService`
  - `MlDatasetLifecycleService`
  - `MlDatasetResponseMapper`
  - small hash/lineage helper
- Feature snapshots:
  - source-specific feature builders for imported rows and internal telemetry
  - `MlFeatureSnapshotLifecycleService`
  - `MlFeatureSnapshotResponseMapper`
  - shared feature hash/schema helper
- Model registry and deployment:
  - `MlModelQueryService`
  - `MlModelLifecycleService`
  - `MlDeploymentPolicyService`
  - `MlDeploymentPolicyResolver`
  - `MlModelApprovalGateVerifier`
  - `MlModelRegistryResponseMapper`
- Recalculation:
  - keep one orchestration entry point
  - extract baseline/feature collection, ML inference request construction,
    fallback decision, prediction persistence, risk/recommendation derivation,
    and event publishing into collaborators.

Use facades only where they protect controller compatibility during the
transition. A facade should delegate immediately and own no business rules.

## Testing Strategy

Add focused unit tests for extracted collaborators before deleting old private
method behavior. Keep existing integration tests passing through the public
services/controllers.

Regression areas:

- dataset preview freshness and candidate selection;
- canonical row materialization and data hashes;
- feature schema/data hashes;
- failure-prediction horizon and label compatibility;
- model approval gate enforcement;
- deployment policy fallback/resolve order;
- analytics recalculation fallback when ML-service is unavailable;
- maintenance `excludeFromMl` propagation.

## Migration Order

1. Extract pure helpers and response mappers first.
2. Extract dataset preview/validation/materialization.
3. Extract feature builders and snapshot lifecycle.
4. Extract model lifecycle and deployment policy services.
5. Extract recalculation collaborators.
6. Remove or shrink compatibility facades once controllers/tests use the new
   boundaries.
