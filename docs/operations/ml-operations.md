# ML Operations

Operational procedures for the production ML module.

For the repeatable Docker production-readiness path, run
[`docs/flows/ml-failure-prediction-docker-verification.md`](../flows/ml-failure-prediction-docker-verification.md).
That smoke scenario exercises dataset materialization, outcome labels, feature
snapshots, ML-service artifacts, approval, deployment, live inference
traceability, and exported monitoring signals without manual database changes.

## Retrain

1. Create or import a dataset version from `/settings/ml` -> `Datasets` or `Import`.
2. Review validation warnings, rejected rows, and lineage hash before training.
3. Record or reconcile maintenance outcomes for the dataset window, then
   generate horizon labels in `Labels`. Confirm positive, normal, unresolved,
   and unobservable counts before supervised failure training.
4. Generate a feature snapshot for a `READY` dataset version. Select the
   feature template that matches the model target, such as `generic-appliance`
   or `fridge-freezer`, and confirm the rolling window and missing-gap
   parameters before running generation.
5. Queue a training run from `Training`.
6. Monitor `analytics.ml.training.runs`, `analytics.ml.training.duration`, and `analytics.ml.training.failures`.
7. Inspect the generated candidate model metrics in `Models`.

Training run state is owned by analytics-service. The analytics worker claims
queued runs, moves them through `RUNNING`, delegates fitting and artifact
creation to ML-service, then performs a model-only smoke inference using the
returned `modelVersionId`. A `CANDIDATE` model is registered only after
ML-service returns a verified artifact manifest and the smoke inference loads
and scores that exact model version.

Model artifacts are stored through ML-service `ArtifactStore`. Production and
Docker profiles use MinIO/S3 with:

- `ML_ARTIFACT_STORE=s3`
- `ML_ARTIFACT_BUCKET=model-artifacts`
- `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, and `MINIO_SECRET_KEY`
- `S3_FORCE_PATH_STYLE=true` for local MinIO compatibility

Local unit tests may use `ML_ARTIFACT_STORE=local`; that mode writes under
`model_registry_path` and is not a production storage boundary. Production
artifact URIs use `s3://<bucket>/<modelVersionId-like-bundle>/...`; inference
loads by the persisted `manifestUri` and verifies `artifactHash` before scoring.

Each claimed run records `leaseOwner`, `leaseExpiresAt`, and `heartbeatAt`.
Workers refresh the lease between training phases. If a worker stops and the
lease expires, the next worker pass returns the stale run to `QUEUED`, increments
`retryCount`, and records `staleRecoveredAt`. Canceling a queued run completes
it as `CANCELED`; canceling a running run sets `cancellationRequested`, and the
worker cooperatively stops before registering a candidate model.

Before training, confirm the feature snapshot shows a nonzero row count,
`featureSchemaHash`, `featureDataHash`, `featureConfigHash`, artifact URI,
immutable row reference, and the expected template id/version. If generation
returns rejected rows or a failure status, use the displayed validation reasons
to fix missing metrics, history windows, device type metadata, or template
selection before queuing training.

Failure-prediction datasets require structured outcome labels. Device-service
owns maintenance outcomes and exposes `/internal/maintenance-outcomes/changes`;
analytics-service projects them using the maintenance reconciliation scheduler
and a separate `device-service-maintenance-outcomes` checkpoint. Generated
labels store `horizonDays`, `generationVersion`, `sourceOutcomeIdsJson`, and
`lineageHash`. Recent rows are intentionally unlabeled until the full horizon
is observable.

## Promote

1. Approve a `CANDIDATE` model in `Models`.
2. Create a `SHADOW` policy first and verify shadow predictions do not change latest active risk.
3. Move to `CANARY` for a limited percentage when supported by the selected scope. Canary inclusion is deterministic per account, device, and model version, so repeated recalculations for the same inputs stay in the same cohort.
4. Promote to `ACTIVE` only after fallback rate, inference failures, and prediction quality remain acceptable.
5. Confirm the selected model version is marked `ACTIVE` and audit logs contain `MODEL_APPROVED` and `POLICY_UPDATED`.

For device-type rollouts, confirm device-service metadata returns the expected
`type` through `/internal/devices/{deviceId}/metadata`. Analytics-service
records the resolved type and policy source in prediction traceability. If a
model's compatible device types or supported metrics do not match runtime
metadata, analytics-service does not call ML-service and persists a fallback
trace with `INCOMPATIBLE_DEVICE_TYPE` or `INCOMPATIBLE_FEATURE_SCHEMA`.

## Roll Back

1. Open `Policies` and select the affected account, device type, or device scope.
2. Set mode to `DISABLED` or call the rollback endpoint for the scope.
3. Keep fallback enabled unless deterministic rule-only behavior is intentionally required.
4. Verify latest predictions exclude shadow records and return baseline or unavailable statuses as expected.
5. Check logs for `mlDeploymentPolicyRolledBack`.

If model-only inference returns `FEATURES_UNAVAILABLE`, regenerate the active
feature window or enable baseline fallback for the affected scope. If it returns
`FEATURE_SCHEMA_MISMATCH`, switch the policy to a model trained with the current
feature template/schema or regenerate features with the model's expected
contract.

If inference returns `MODEL_UNAVAILABLE`, first check `GET /health/artifacts`
on ML-service and MinIO bucket access, then verify the model version has
`artifactUri`, `artifactManifestUri`, `artifactHash`, and positive
`artifactSizeBytes`. Roll back to the previous active policy or disable the
policy with fallback enabled while restoring the missing object.

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
- `analytics.maintenance.outcome.reconciliation.projected`
- `analytics.ml.training.runs`
- `analytics.ml.training.duration`
- `analytics.ml.training.failures`
- `analytics.ml.inference.duration`
- `analytics.ml.inference.failures`
- `analytics.ml.inference.fallback.rate`
- `analytics.ml.inference.fallback.count`
- `analytics.ml.inference.feature_mismatch.count`
- `analytics.ml.inference.missing_feature.count`
- `analytics.ml.inference.shadow.count`
- `analytics.ml.inference.canary.included.count`
- `analytics.ml.inference.canary.excluded.count`
- `analytics.ml.drift.active.feature_schema_hashes`
- `analytics.ml.quality.false_alerts.count`
- `analytics.ml.quality.false_alerts.per_device_month`
- `analytics.ml.quality.lead_time.days.avg`

Inference latency and failure metrics are tagged with `modelVersionId` when a
policy selected a model version. Fallback count is tagged by reason for canary
exclusion, incompatible model/device metadata, maintenance suppression, disabled
policy, and ML unavailability.
False-alert and lead-time gauges are populated only after labels/outcomes exist;
empty label windows report `0` rather than failing metric collection.

Health checks:

- `GET /health` verifies ML-service process liveness.
- `GET /health/artifacts` verifies the configured artifact provider can be
  accessed. Kubernetes readiness uses this endpoint so a pod does not accept
  trained-model traffic until MinIO/S3 is reachable.

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
