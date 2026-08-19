## Why

Current ML verification proves the production failure-prediction happy path in
Docker, including dataset creation, labels, feature snapshots, training,
artifact persistence, deployment, and live trained-model prediction. It does
not yet define a complete release gate for every ML serving path, model family,
negative path, frontend workflow, and downstream alert/incident behavior.

VoltCanary needs an explicit ML verification change so engineers can answer:

- Do baseline heuristic, anomaly-detection, and failure-prediction models all
  score realistic inputs?
- Does the UI let an operator create data, train models, approve, deploy, and
  inspect live predictions?
- Do high-risk ML outputs produce user-visible incidents through the alert
  pipeline, with deduplication, lifecycle, and notification evidence?
- Are fallback, model-unavailable, schema-mismatch, maintenance-exclusion, and
  resilience cases tested rather than assumed?

## What Changes

- Define a full ML verification matrix covering model correctness, lifecycle,
  serving, UI workflows, monitoring, and alert/incident outcomes.
- Add a repeatable test process for local Docker, CI-safe gates, and manual
  release evidence.
- Require E2E evidence that ML predictions can create or suppress alerts as
  expected.
- Require tests for every supported model family and serving path:
  `BASELINE_HEURISTIC`, `ANOMALY_DETECTION`, and `FAILURE_PREDICTION`.
- Require failure-mode tests for insufficient data, unavailable artifacts,
  feature mismatch, internal auth, maintenance exclusion, fallback policy,
  shadow/canary isolation, and cross-account isolation.

## Capabilities

### Changed Capabilities

- `ml-predictive-analytics`: expands verification requirements for all model
  serving paths and alert/incident outcomes.
- `ml-failure-prediction`: expands failure-model release gates beyond the
  current happy path.
- `ml-console-workflows`: requires frontend E2E verification for operator
  workflows.
- `ml-deployment-operations`: requires alert/incident and monitoring evidence
  for active, shadow, canary, and fallback deployments.

## Impact

- `scripts/`: expected home for new repeatable smoke/check scripts.
- `docs/flows/`: expected home for the full ML verification runbook.
- `backend/services/analytics-service/**`: model selection, prediction
  traceability, event publication, and alert-trigger integration tests.
- `backend/services/alert-service/**`: analytics-event to alert/incident
  processing, deduplication, lifecycle, and notification tests.
- `ml-service/**`: model scoring, training, artifact, auth, and failure-mode
  tests.
- `frontend/src/**`: ML Settings, device prediction, models, policies, and
  alerts UI E2E tests.
- `deploy/` and CI workflows: optional promotion of the matrix into a required
  gate for ML-affecting changes.

## Non-Goals

- This change does not rename alerts to incidents. In the current product,
  user-visible incidents are represented by alert-service alerts with lifecycle
  states `OPEN`, `ACKNOWLEDGED`, and `RESOLVED`.
- This change does not require production data access. Test data must be
  synthetic, deterministic, and tenant-scoped.
