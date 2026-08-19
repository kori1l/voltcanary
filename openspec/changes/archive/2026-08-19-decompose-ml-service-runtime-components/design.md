# Design: Decompose ML-Service Runtime Components

## Current State

ML-service routes are thin, but service modules are broad. The current shape
has useful coverage yet makes runtime changes expensive because artifact I/O,
model-specific behavior, error responses, and settings access are coupled.

Observed boundary mixing:

- Training service owns both orchestration and model-family-specific training
  implementations.
- Failure approval gate construction and model card generation are embedded in
  training.
- Inference service owns heuristic scoring, trained model loading, feature
  extraction, failure-specific responses, and model-unavailable response
  construction.
- Artifact store owns provider selection, local and S3 implementation details,
  health/readiness, cleanup impact, hash calculation, and boto3 client setup.
- Tests override singleton settings fields directly.

## Target Decomposition

Training:

- `TrainingRunCoordinator` for idempotency, timing, cancellation checkpoints,
  and response status.
- `AnomalyTrainingRunner` and `FailurePredictionTrainingRunner` for
  model-family behavior.
- `ModelArtifactPublisher` for bundle manifest construction, persistence, and
  verification.
- `ApprovalGateEvaluator` and `ModelCardBuilder` for failure model reporting.

Inference:

- `BaselineHeuristicScorer` for fallback scoring.
- `TrainedModelResolver` for selected model metadata and cache lookup.
- `AnomalyModelScorer` and `FailureModelScorer` for model-family scoring.
- `InferenceResponseFactory` for unavailable/feature-error responses.

Artifact storage:

- shared `ArtifactStore` protocol or facade;
- `LocalArtifactStoreProvider`;
- `S3ArtifactStoreProvider`;
- `ArtifactBundleVerifier`;
- `ArtifactCleanupPlanner`.

Settings:

- production app keeps a default settings provider;
- tests use fixture-scoped settings instances or monkeypatch helpers that
  restore state automatically;
- middleware and stores accept settings providers instead of reaching into the
  global singleton at runtime-critical points.

## Compatibility

Route handlers may continue to instantiate default services while the
underlying service constructors accept explicit collaborators for tests.

Existing response schemas and aliases remain backward-compatible. Any internal
module names are implementation details.

## Verification Strategy

- Unit tests for each extracted scorer, gate evaluator, artifact provider, and
  response factory.
- API tests through FastAPI `TestClient` for route compatibility.
- Existing lifecycle tests remain as integration coverage but should be split
  into smaller files by workflow after extraction.
