# ML Failure Prediction Docker Verification

This runbook verifies the complete failure prediction lifecycle in the local
Docker stack without manual database intervention. It is intended as the
repeatable production-readiness smoke path for the ML workflow.

## Prerequisites

- Docker Desktop or compatible Docker Engine is running.
- Repository dependencies are installed with `npm install` at the repo root.
- Default local ports are available: `8080`, `8085`, `8090`, `9000`, and
  optionally `9090` for Prometheus.

Start the stack and run the verification:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-failure-prediction-docker-smoke.ps1 -StartStack
```

If the stack is already running, omit `-StartStack`:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-failure-prediction-docker-smoke.ps1
```

For a broader destructive-lifecycle check, add `-VerifyLifecycleCleanup`:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-failure-prediction-docker-smoke.ps1 -VerifyLifecycleCleanup
```

The script creates a unique user, account, washer device, API data source,
source token, backfilled telemetry, maintenance outcome, dataset version,
horizon labels, feature snapshot, training run, candidate model, approval,
active deployment policy, and live recalculation job. It uses only HTTP APIs and
Actuator metrics. With `-VerifyLifecycleCleanup`, it also verifies lifecycle
impact previews, blocked destructive actions, manual label deletion, policy
disable/delete, and archive paths for training, feature snapshot, and dataset
lineage.

## Verification Coverage

| Requirement | Verification step |
| --- | --- |
| Dataset version | Creates an internal telemetry dataset and asserts `status=READY`, `acceptedRows >= 24`, and a returned `datasetVersionId`. |
| Immutable dataset rows | Uses the dataset API materialization path and later trains from the returned `datasetVersionId`; no database inserts are used. |
| Maintenance outcome labels | Creates a `CORRECTIVE_MAINTENANCE` outcome, waits for analytics reconciliation, generates horizon labels, and asserts positive and normal labels plus `lineageHash`. |
| Feature snapshot | Generates `FAILURE_PREDICTION` features for the same horizon and asserts `rowCount`, `featureSchemaHash`, `featureDataHash`, and `artifactUri`. |
| Real model artifact | Queues a failure prediction training run, waits for `SUCCEEDED`, then asserts candidate model `artifactUri`, `artifactManifestUri`, `sha256` artifact hash, and positive artifact size. |
| Approval gate | Calls the model approval API, which rejects incomplete artifact metadata, and asserts the model becomes `APPROVED`. |
| Deployment policy | Creates an `ACTIVE` device policy with fallback enabled and asserts it selects the trained `modelVersionId`. |
| Live inference trace | Runs analytics recalculation, waits for `COMPLETED`, then asserts latest prediction is `TRAINED_MODEL`, `FAILURE_PREDICTION`, uses the deployed `modelVersionId`, and includes `failureRiskScore`, `failureHorizonDays`, `riskDrivers`, and `inputHash`. |
| Monitoring signals | Reads `analytics-service` `/actuator/prometheus` and asserts feature generation, training run, inference duration, and inference fallback metrics are exported. |
| Lifecycle cleanup | Optional `-VerifyLifecycleCleanup` creates and deletes a manual label, checks generated-label delete is blocked, verifies active policy delete is blocked until disable, deletes the disabled policy, checks model delete is blocked by runtime or prediction lineage, and archives referenced training, snapshot, and dataset records. |

## Expected Output

On success, the script prints a JSON object similar to:

```json
{
  "runId": "0f1a2b3c4d",
  "accountId": "9e2d...",
  "deviceId": "4c2f...",
  "datasetVersionId": "a12f...",
  "featureSnapshotId": "b34a...",
  "horizonLabelLineageHash": "sha256:...",
  "trainingRunId": "c56d...",
  "modelVersionId": "d78e...",
  "artifactUri": "s3://model-artifacts/...",
  "artifactManifestUri": "s3://model-artifacts/...",
  "predictionStatus": "TRAINED_MODEL",
  "failureRiskScore": 0.42,
  "inputHash": "...",
  "lifecycleCleanupVerified": true
}
```

Keep this output with release evidence when using the runbook as a production
readiness gate.

## Failure Triage

- Failure before telemetry persistence usually means Kafka, telemetry-service,
  or ingestion source-token validation is unavailable.
- Failure waiting for positive horizon labels usually means maintenance outcome
  reconciliation has not run; rerun with `-StartStack` or wait one scheduler
  interval and rerun the script.
- Training failure with insufficient labels means the generated telemetry window
  or outcome projection did not produce both positive and normal horizon labels.
- `FEATURES_UNAVAILABLE`, `FEATURE_SCHEMA_MISMATCH`, or a fallback prediction in
  the final trace means online/offline feature parity or model artifact loading
  regressed.
- Missing Prometheus metric names means analytics-service Actuator exposure or
  ML metric registration regressed.
