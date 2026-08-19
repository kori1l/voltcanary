# ML Verification Report - 2026-08-19

## Summary

Status: PASSED

The current Docker stack was verified end-to-end for the ML failure-prediction
workflow. The run created synthetic telemetry, materialized a dataset, generated
horizon labels and a feature snapshot, trained a model, stored model artifacts
in MinIO/S3, approved and deployed the model, recalculated live analytics, and
confirmed that the latest prediction used the trained model rather than a
fallback path.

Frontend verification also passed: the ML console displayed the generated
dataset, training run, model, and latest prediction, and a new training run was
queued from the UI and completed successfully.

## Environment

- Repository: `D:\canary\voltcanary`
- Date: 2026-08-19
- Docker Compose project: `voltcanary`
- Frontend: `http://localhost:3000`
- API Gateway: `http://localhost:8080`
- Analytics service: `http://localhost:8085`
- ML service: `http://localhost:8090`
- Artifact store: MinIO/S3, bucket `model-artifacts`

## Service Health

| Check | Endpoint | Result |
| --- | --- | --- |
| API Gateway | `GET http://localhost:8080/actuator/health` | `UP` |
| Analytics service | `GET http://localhost:8085/actuator/health` | `UP` |
| ML service liveness | `GET http://localhost:8090/health` | `ok` |
| ML artifact readiness | `GET http://localhost:8090/health/artifacts` | `ok`, provider `s3`, bucket `model-artifacts` |

## Automated Docker ML Smoke

Command:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-failure-prediction-docker-smoke.ps1 -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-production-smoke-summary.json
```

Result: PASSED

Covered scenarios:

| Scenario | Result |
| --- | --- |
| Register verification user and login through gateway | PASSED |
| Create device and API data source token | PASSED |
| Ingest synthetic 28-day telemetry backfill | PASSED |
| Verify telemetry persistence through telemetry API | PASSED |
| Create corrective maintenance outcome for failure labels | PASSED |
| Preview internal telemetry dataset with failure-readiness labels | PASSED |
| Create `READY` internal telemetry dataset | PASSED |
| Generate positive and normal horizon labels | PASSED |
| Generate `FAILURE_PREDICTION` feature snapshot | PASSED |
| Queue and complete ML training run | PASSED |
| Register candidate model with artifact metadata | PASSED |
| Approve model and create active device deployment policy | PASSED |
| Recalculate live analytics and verify trained-model trace | PASSED |
| Verify required Prometheus ML metric names are exported | PASSED |

Key output:

| Field | Value |
| --- | --- |
| runId | `721a619254` |
| userId | `b7bd8152-ffd0-4e6f-9dab-941f925a1f8f` |
| accountId | `1683f6de-dfe8-4601-a7d8-abe70ea1fc4c` |
| deviceId | `9743004e-9006-4f49-b206-f6652ac88ec1` |
| datasetVersionId | `176ca1a2-f018-4118-adff-0d8473d27a3b` |
| featureSnapshotId | `9c2bc933-8054-4d87-a0b3-da22ed807ef9` |
| trainingRunId | `d967aa29-fece-493d-8d97-1ceb290c5dd3` |
| modelVersionId | `7761c23b-04f3-4201-96dd-27426c7e609b` |
| artifactHash | `sha256:858d969ef5d45f1d4d498e3d8e344610888b5fb48e085528421db29757a3fb50` |
| featureSchemaHash | `sha256:af5e10c55bbba95bac02c73e0502128339f76118ce62f9540ca8352a404557f1` |
| artifactUri | `s3://model-artifacts/model-d967aa29-fece-493d-8d97-1ceb290c5dd3/model.joblib` |
| artifactManifestUri | `s3://model-artifacts/model-d967aa29-fece-493d-8d97-1ceb290c5dd3/manifest.json` |
| predictionStatus | `TRAINED_MODEL` |
| policySource | `DEVICE` |
| fallbackReason | empty |
| failureRiskScore | `0.054` |
| failureHorizonDays | `7` |
| inputHash | `95c38702c33ced2066d5509e958de2095387840303df8d80b434109697b1d052` |

Detailed JSON evidence: `artifacts/ml-production-smoke-summary.json`.

## Frontend Verification

Tooling: temporary Playwright install outside the repository, launched against
the running Docker frontend at `http://localhost:3000`.

Verification user:

- Email: `ml-smoke-721a619254@example.test`
- Password: `VoltCanary!2026-721a619254`

Scenarios:

| Scenario | Result | Evidence |
| --- | --- | --- |
| Open frontend and render login page | PASSED | Page title `VoltCanary`, login form visible |
| Login as smoke user | PASSED | Redirected into authenticated app |
| Open `/settings/ml` | PASSED | `ML Settings` page rendered |
| Datasets tab displays generated dataset | PASSED | Dataset `Docker failure prediction dataset 721a619254`, status `READY` |
| Training tab displays successful training run and queue form | PASSED | `SUCCEEDED` run visible; `Queue training` form visible |
| Models tab displays trained model metadata | PASSED | Approved/active model visible with artifact hash |
| Monitoring tab displays operations snapshot | PASSED | Ready datasets and active models counters visible |
| Load device latest prediction from UI | PASSED | Requests returned `200`; latest prediction showed `TRAINED_MODEL`, model id, failure risk, `DEVICE / ACTIVE`, fallback `None` |
| Queue a new training run from frontend | PASSED | UI POST returned `200`, initial status `QUEUED`, final status `SUCCEEDED` |

Frontend-created training run:

| Field | Value |
| --- | --- |
| trainingRunId | `0f0fc310-2c66-48bb-bda5-ce722677ffde` |
| createdFrom | Frontend `Training` tab, `Queue training` button |
| datasetVersionId | `176ca1a2-f018-4118-adff-0d8473d27a3b` |
| featureSnapshotId | `9c2bc933-8054-4d87-a0b3-da22ed807ef9` |
| modelFamily | `FAILURE_PREDICTION` |
| horizonDays | `7` |
| minimumPositiveLabels | `1` |
| minimumLabelCoverage | `0.01` |
| finalStatus | `SUCCEEDED` |

Screenshot evidence:

- `artifacts/ml-frontend-settings.png`
- `artifacts/ml-frontend-datasets.png`
- `artifacts/ml-frontend-training.png`
- `artifacts/ml-frontend-models.png`
- `artifacts/ml-frontend-device-prediction-loaded.png`
- `artifacts/ml-frontend-training-before-queue.png`
- `artifacts/ml-frontend-training-after-queue.png`

## Repository Validation

Command:

```powershell
npm run openspec:validate
```

Result: PASSED

OpenSpec validation result:

- `spec/backend-architecture`: passed
- `spec/dependency-upgrade`: passed
- `spec/ml-artifact-runtime`: passed
- `spec/ml-console-workflows`: passed
- `spec/ml-dataset-lifecycle`: passed
- `spec/ml-deployment-operations`: passed
- `spec/ml-failure-outcomes`: passed
- `spec/ml-failure-prediction`: passed
- `spec/ml-feature-platform`: passed
- `spec/ml-predictive-analytics`: passed
- Total: 10 passed, 0 failed

## Notes

- No implementation code was changed during verification.
- `artifacts/ml-production-smoke-summary.json` was refreshed with the latest
  successful smoke run.
- Frontend screenshots were added as run evidence under `artifacts/`.
- The Docker stack remained running after the verification.
