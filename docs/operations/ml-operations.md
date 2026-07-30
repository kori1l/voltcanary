# ML Operations

Operational procedures for the production ML module.

## Retrain

1. Create or import a dataset version from `/settings/ml` -> `Datasets` or `Import`.
2. Review validation warnings, rejected rows, and lineage hash before training.
3. Generate a feature snapshot for a `READY` dataset version.
4. Queue a training run from `Training`.
5. Monitor `analytics.ml.training.runs`, `analytics.ml.training.duration`, and `analytics.ml.training.failures`.
6. Inspect the generated candidate model metrics in `Models`.

## Promote

1. Approve a `CANDIDATE` model in `Models`.
2. Create a `SHADOW` policy first and verify shadow predictions do not change latest active risk.
3. Move to `CANARY` for a limited percentage when supported by the selected scope.
4. Promote to `ACTIVE` only after fallback rate, inference failures, and prediction quality remain acceptable.
5. Confirm the selected model version is marked `ACTIVE` and audit logs contain `MODEL_APPROVED` and `POLICY_UPDATED`.

## Roll Back

1. Open `Policies` and select the affected account, device type, or device scope.
2. Set mode to `DISABLED` or call the rollback endpoint for the scope.
3. Keep fallback enabled unless deterministic rule-only behavior is intentionally required.
4. Verify latest predictions exclude shadow records and return baseline or unavailable statuses as expected.
5. Check logs for `mlDeploymentPolicyRolledBack`.

## Delete Imported Datasets

The first implementation retains imported datasets for auditability and does not expose a hard-delete API.

1. Stop using the imported dataset in new training runs.
2. Archive or reject models trained from the imported dataset.
3. Roll back policies pointing to those models.
4. Mark downstream operational records as retired in the tracking system.
5. Remove object storage artifacts only after retention and audit requirements are satisfied.

## Observability

Prometheus metrics:

- `analytics.ml.dataset.versions`
- `analytics.ml.dataset.validation.rejected.rows`
- `analytics.ml.feature.snapshots.generated`
- `analytics.ml.feature.generation.completed`
- `analytics.ml.training.runs`
- `analytics.ml.training.duration`
- `analytics.ml.training.failures`
- `analytics.ml.inference.duration`
- `analytics.ml.inference.failures`
- `analytics.ml.inference.fallback.rate`
- `analytics.ml.drift.active.feature_schema_hashes`

Structured log events:

- `mlDatasetCreated`
- `mlExternalImportCompleted`
- `mlFeatureSnapshotGenerated`
- `mlTrainingStarted`
- `mlTrainingSucceeded`
- `mlTrainingFailed`
- `mlModelPromoted`
- `mlModelRejected`
- `mlModelArchived`
- `mlDeploymentPolicyUpdated`
- `mlDeploymentPolicyRolledBack`
- `mlInferenceFailed`
