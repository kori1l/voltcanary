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

Model artifacts are stored through ML-service `ArtifactStore`. Production-like
runtime profiles use MinIO/S3 with:

- `ML_RUNTIME_PROFILE=docker|kubernetes|production`
- `ML_ARTIFACT_STORE=s3`
- `ML_ARTIFACT_BUCKET=model-artifacts`
- `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, and `MINIO_SECRET_KEY`
- `S3_FORCE_PATH_STYLE=true` for local MinIO compatibility

Local unit tests may use `ML_ARTIFACT_STORE=local`; that mode writes under
`model_registry_path` and is not a production storage boundary. Production
artifact URIs use `s3://<bucket>/<modelVersionId-like-bundle>/...`; inference
loads by the persisted `manifestUri` and verifies `artifactHash` before scoring.
If `ML_RUNTIME_PROFILE` is one of `production`, `prod`, `docker`,
`kubernetes`, or `k8s`, `/health/artifacts` fails with
`local_artifact_store_forbidden` when `ML_ARTIFACT_STORE=local`.

ML-service internal auth must be explicit in each runtime profile:

- `ML_INTERNAL_AUTH_REQUIRED=true` for Docker and Kubernetes production-like
  runs.
- `ML_INTERNAL_SERVICE_TOKEN` on ML-service must match
  `INTERNAL_SERVICE_TOKEN` used by analytics-service.
- `ML_ALLOWED_SOURCE_SERVICES=analytics-service` keeps the caller allowlist
  narrow.
- `ML_MAX_REQUEST_BYTES` limits `/train`, `/inference`, and `/ml/*` execution
  aliases before request parsing.
- Local and unit-test profiles may disable auth only by explicitly setting
  `ML_INTERNAL_AUTH_REQUIRED=false` or by using a test token.

Keep `GET /health` and `GET /health/artifacts` unauthenticated. All other
ML-service endpoints should be treated as private service-to-service endpoints.
Auth failures and request rejections are logged as sanitized events without
tokens, stack traces, raw request bodies, or internal object-store details.

Artifact cleanup must start with a dry-run report. The report compares local or
S3 artifacts with references supplied from analytics-service metadata:
training runs, model versions, deployment policies, prediction traceability,
and audit records. Artifacts referenced by any of those sources are blocked
from deletion. Unreferenced artifacts are candidates only; destructive deletion
requires a separate operator action and must not remove active, policy-backed,
prediction-backed, or audit-required model lineage.

`ml-service/models/` is only the default local development output path. The
repository ignores generated feature snapshots and model bundles in that
directory; tests should use temporary directories or explicit fixtures outside
the runtime registry path.

Each claimed run records `leaseOwner`, `leaseExpiresAt`, and `heartbeatAt`.
Workers refresh the lease between training phases. If a worker stops and the
lease expires, the next worker pass returns the stale run to `QUEUED`, increments
`retryCount`, and records `staleRecoveredAt`. Canceling a queued run completes
it as `CANCELED`; canceling a running run sets `cancellationRequested`, and the
worker cooperatively stops before registering a candidate model.

When analytics-service delegates fitting to ML-service it sends the durable
`trainingRunId`, a stable `idempotencyKey` derived from that run, and a
deterministic `artifactBundleId`. Retrying the same claimed run reuses the same
key so ML-service can return an already verified artifact response instead of
writing a duplicate bundle. If the same key is replayed with different training
metadata, ML-service rejects the call as an idempotency conflict and does not
overwrite artifact metadata.

ML-service checks cooperative cancellation and timeout budgets before major
training phases and again before final artifact promotion. A canceled or timed
out run returns a terminal non-success response without a candidate-ready
manifest. If artifact persistence or verification fails after partial files are
written, ML-service removes the deterministic final bundle before surfacing the
failure; operators should investigate object-store write/credential errors and
retry the analytics-owned run rather than manually registering partial
artifacts.

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

Readiness failure codes:

- `local_artifact_store_forbidden`: set `ML_ARTIFACT_STORE=s3` for the current
  production-like `ML_RUNTIME_PROFILE`.
- `s3_credentials_missing`: provide MinIO/S3 access key and secret through the
  secret manager or local `.env`.
- `artifact_store_unavailable`: verify the `model-artifacts` bucket exists,
  credentials can `HeadBucket`, and network/DNS can reach MinIO/S3.
- `unsupported_artifact_store`: use `local` only for local/test or `s3` for
  production-like runtime profiles.

## Delete Imported Datasets

Dataset cleanup is intentionally conservative. Use lifecycle actions from the
ML console or API instead of direct database edits:

1. Disable deployment policies that point at models trained from the dataset.
2. Reject or archive candidate and inactive models.
3. Archive successful training runs, feature snapshots, and ready datasets when lineage must remain readable.
4. Delete only failed or draft datasets, failed or canceled training runs, unreferenced snapshots, unreferenced candidate/rejected models, inactive policies, and manual labels.
5. Check `GET .../lifecycle-impact?action=...` before destructive actions and use the dependency summary in the confirmation.
6. When an API still returns `409`, treat it as a race with newly attached lineage and disable/archive dependent state first.

All lifecycle actions require an operator reason. Generated labels should be
regenerated or superseded for the same dataset/horizon/config; manual labels can
be deleted with audit metadata. Do not remove object storage artifacts while a
model, prediction, audit event, or training run still references them.

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
- Health probes remain unauthenticated even when
  `ML_INTERNAL_AUTH_REQUIRED=true`.
- In production-like runtime profiles, readiness also verifies that local
  source-tree artifact storage is disabled.

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
- `mlModelDeleted`
- `mlDeploymentPolicyUpdated`
- `mlDeploymentPolicyRolledBack`
- `mlDeploymentPolicyDisabled`
- `mlDeploymentPolicyDeleted`
- `mlInferenceFailed`
- `mlInternalAuthRejected`
- `mlRequestRejected`
- `mlRequestValidationRejected`
