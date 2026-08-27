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

For CI or non-interactive runs, use `-CiMode` and write the compact evidence
artifact with `-SummaryPath`:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-failure-prediction-docker-smoke.ps1 -StartStack -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-production-smoke-summary.json
```

On GitHub Actions/Linux the workflow uses PowerShell Core:

```bash
pwsh ./scripts/ml-failure-prediction-docker-smoke.ps1 -StartStack -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-production-smoke-summary.json
```

The script creates a unique user, account, washer device, API data source,
source token, backfilled telemetry, maintenance outcome, dataset version,
horizon labels, feature snapshot, training run, candidate model, approval,
active deployment policy, and live recalculation job. It uses only HTTP APIs and
Actuator metrics. With `-VerifyLifecycleCleanup`, it also verifies lifecycle
impact previews, blocked destructive actions, manual label deletion, policy
disable/delete, and archive paths for training, feature snapshot, and dataset
lineage.

The training request uses a `failure-docker-smoke-ci` approval profile for the
synthetic dataset. It keeps precision, recall, PR-AUC/lift, false-alert,
artifact-load, and feature-schema gates active, while setting
`minimumLeadTimeDays=0` because the generated fixture has deterministic labels
but does not model operational lead-time evidence.

The CI job `ML Production Smoke` in `.github/workflows/ci.yml` runs for
`workflow_dispatch` and for pull requests or pushes that touch ML-service,
analytics ML lifecycle code, ML API/operations/flow docs, ML OpenSpec specs,
Docker Compose wiring, or the smoke script. It uploads
`ml-production-smoke-summary` when the script creates a summary file. In CI
mode, failures write a compact summary with `status=FAILED`, the current
lifecycle step, the sanitized error message, and any resource ids created before
the failure.

## Verification Coverage

| Requirement | Verification step |
| --- | --- |
| Dataset version | Creates an internal telemetry dataset and asserts `status=READY`, `acceptedRows >= 24`, and a returned `datasetVersionId`. |
| Immutable dataset rows | Uses the dataset API materialization path and later trains from the returned `datasetVersionId`; no database inserts are used. |
| Maintenance outcome labels | Creates a `CORRECTIVE_MAINTENANCE` outcome, waits for analytics reconciliation, generates horizon labels, and asserts positive and normal labels plus `lineageHash`. |
| Feature snapshot | Generates `FAILURE_PREDICTION` features for the same horizon and asserts `rowCount`, `featureSchemaHash`, `featureDataHash`, and `artifactUri`. |
| Real model artifact | Queues a failure prediction training run, waits for `SUCCEEDED`, then asserts candidate model `artifactUri`, `artifactManifestUri`, `sha256` artifact hash, matching training artifact hash, matching feature schema hash, and positive artifact size. |
| Approval gate | Calls the model approval API, which rejects incomplete artifact metadata, and asserts the model becomes `APPROVED`. |
| Deployment policy | Creates an `ACTIVE` device policy with fallback enabled and asserts it selects the trained `modelVersionId`. |
| Live inference trace | Runs analytics recalculation, waits for `COMPLETED`, then asserts latest prediction is `TRAINED_MODEL`, `FAILURE_PREDICTION`, uses the deployed `modelVersionId`, has `policySource=DEVICE`, has an empty `fallbackReason`, and includes `failureRiskScore`, `failureHorizonDays`, `riskDrivers`, and `inputHash`. |
| Monitoring signals | Reads `analytics-service` `/actuator/prometheus` and asserts feature generation, training run, inference duration, fallback rate/count, feature-mismatch/missing-feature error gauges, shadow, canary, false-alert, and lead-time quality metrics are exported. |
| Lifecycle cleanup | Optional `-VerifyLifecycleCleanup` creates and deletes a manual label, checks generated-label delete is blocked, verifies active policy delete is blocked until disable, deletes the disabled policy, checks model delete is blocked by runtime or prediction lineage, and archives referenced training, snapshot, and dataset records. |

## Expected Output

On success, the script prints a JSON object similar to:

```json
{
  "status": "PASSED",
  "runId": "0f1a2b3c4d",
  "completedStep": "Docker smoke verification passed",
  "accountId": "9e2d...",
  "deviceId": "4c2f...",
  "datasetVersionId": "a12f...",
  "featureSnapshotId": "b34a...",
  "horizonLabelLineageHash": "sha256:...",
  "trainingRunId": "c56d...",
  "modelVersionId": "d78e...",
  "artifactHash": "sha256:...",
  "featureSchemaHash": "sha256:...",
  "artifactUri": "s3://model-artifacts/...",
  "artifactManifestUri": "s3://model-artifacts/...",
  "policySource": "DEVICE",
  "fallbackReason": "",
  "predictionStatus": "TRAINED_MODEL",
  "failureRiskScore": 0.42,
  "failureHorizonDays": 7,
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
