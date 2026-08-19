# ML Model Training And Alert Guide

This guide describes the operator path for creating ML training assets from the
frontend, training a model, deploying it, and verifying that ML-driven analytics
can create alerts.

The frontend does not call `ml-service` directly. The UI calls the API Gateway,
analytics-service owns the dataset, label, feature, training, model registry,
policy, and prediction metadata, and analytics-service calls `ml-service` across
the internal service boundary.

## Prerequisites

- Local stack is running: `docker compose --profile app --profile observability up -d`
- Frontend is available at `http://localhost:3000`.
- API Gateway is available at `http://localhost:8080`.
- The account has at least one device with enough telemetry for the selected
  model family.
- For `FAILURE_PREDICTION`, the selected dataset window must contain failure or
  corrective-maintenance outcomes that can produce positive horizon labels.

Run this first when you need a deterministic local fixture:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-failure-prediction-docker-smoke.ps1 -StartStack -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-production-smoke-summary.json
```

The smoke creates a user, device, telemetry backfill, maintenance outcome,
dataset, labels, feature snapshot, training run, model, policy, and trained
prediction through HTTP APIs. Use it to seed data before taking screenshots or
before manually checking the frontend.

After the smoke passes, use the printed `runId` to log in:

```text
email: ml-smoke-<runId>@example.test
password: VoltCanary!2026-<runId>
```

The seeded device is named `ML smoke washer <runId>`.

## 1. Open ML Settings

Log in to the frontend, then open `Settings -> ML Settings` or go directly to
`/settings/ml`.

The workflow rail at the top is the fastest readiness check. For a trainable
failure model, the rail should move through these states:

```text
Dataset -> Labels -> Feature snapshot -> Training -> Model -> Policy -> Monitoring
```

![ML Settings desktop](../../artifacts/screenshots/ml-full-verification/ml-settings-desktop.png)

## 2. Create A Dataset Version

Open the `Datasets` tab.

1. Choose the workflow mode. Use `Failure prediction` when the model output
   must be failure risk. Use `Anomaly detection` when the model output should
   detect unusual telemetry patterns.
2. Select one or more devices.
3. Set the time window, split strategy, metric selection mode, and quality gate
   values.
4. For `FAILURE_PREDICTION`, set a positive `horizonDays` value.
5. Preview the dataset. The UI must show backend-returned metrics and quality
   gates. If the preview is stale or blocked, `Create dataset version` stays
   disabled.
6. Click `Create dataset version` only after the preview is `READY` or
   `READY_WITH_WARNINGS`.

Expected result:

- The dataset appears in the dataset list.
- `status` is `READY`.
- Detail view includes accepted rows, validation summary, immutable row
  reference or artifact hash, and lineage.

![Dataset workbench](../../artifacts/ml-frontend-datasets.png)

## 3. Generate Horizon Labels

For `FAILURE_PREDICTION`, open the `Labels` tab.

1. Select the same dataset version and the same horizon used for the dataset
   preview.
2. Click `Generate horizon labels`.
3. If the job is still running, click `Check generation status` until it
   reaches `COMPLETED`.
4. Confirm positive and normal labels are present.

Expected result:

- Generated labels include a `lineageHash`.
- Positive labels come from `CONFIRMED_FAILURE` or `CORRECTIVE_MAINTENANCE`
  outcomes inside the horizon.
- Generated labels are lineage-managed and cannot be hard-deleted through the
  manual label delete path.

## 4. Generate A Feature Snapshot

Return to the `Datasets` tab and generate a feature snapshot.

For failure prediction:

- `modelFamily`: `FAILURE_PREDICTION`
- `horizonDays`: same value used for horizon labels and training
- Feature families: include `rolling_stats`, `baseline_deviation`,
  `missing_data`, `time`, `device_context`, and `label`

Expected result:

- Snapshot row count is positive.
- Snapshot includes `featureSchemaHash`, `featureDataHash`, `featureConfigHash`,
  and an artifact URI or immutable row reference.
- The snapshot is visible in the training form as a compatible snapshot for the
  selected dataset and model family.

## 5. Queue Training From The Frontend

Open the `Training` tab.

1. Select `Failure prediction` or `Anomaly detection`.
2. Select the `READY` dataset version.
3. Select a compatible feature snapshot.
4. Select the algorithm.
5. For `FAILURE_PREDICTION`, set `Prediction horizon`, `Minimum positive
   labels`, and `Minimum label coverage`.
6. Click `Queue training`.

The failure-prediction training form should show the prerequisite checklist and
block submission when the snapshot family or horizon does not match the
training request.

![Training before queue](../../artifacts/ml-frontend-training-before-queue.png)

Expected result:

- A new training run is created with initial status `QUEUED`.
- The worker moves the run through `RUNNING` to `SUCCEEDED`, or stores a
  sanitized `FAILED` reason.
- A successful run records artifact URI, artifact hash, metrics, and registers
  a candidate model version.

![Training after queue](../../artifacts/ml-frontend-training-after-queue.png)

## 6. Review And Approve The Model

Open the `Models` tab.

1. Review the model family, algorithm, dataset version, feature snapshot,
   artifact metadata, supported metrics, and compatible device types.
2. For failure models, review promotion gates. The model can be promoted only
   when required gates pass and `promotionEligible=true`.
3. Click `Approve` for a valid candidate model.

Expected result:

- The candidate model becomes `APPROVED`.
- A model missing artifact metadata, feature schema compatibility, or required
  failure-prediction approval gates cannot be approved.

![Models](../../artifacts/ml-frontend-models.png)

## 7. Deploy A Policy

Open the `Policies` tab.

1. Choose scope: `DEVICE`, `DEVICE_TYPE`, or `ACCOUNT`.
2. Select the approved model version.
3. Choose `ACTIVE`, `CANARY`, or `SHADOW`.
4. Keep fallback enabled unless the verification explicitly requires
   model-only behavior.
5. Save the policy.
6. Use `Resolve policy` for a target device to confirm the effective source and
   selected model.

Expected result:

- `ACTIVE` policy creation marks the selected model as active.
- Runtime policy resolution returns the expected model version, policy source,
  deployment mode, canary state when applicable, and fallback setting.

## 8. Verify Latest Prediction

Open the `Settings` or `Monitoring` tab and load the target device.

If needed, trigger analytics recalculation from the device workflow or through
the API:

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/analytics/devices/<deviceId>/recalculate" -Headers @{ Authorization = "Bearer <accessToken>" }
```

Expected result for an active trained failure model:

- `predictionStatus` is `TRAINED_MODEL`.
- `modelFamily` is `FAILURE_PREDICTION`.
- `modelVersionId` matches the deployed policy.
- `policySource` is the expected scope, for example `DEVICE`.
- `deploymentMode` is `ACTIVE`, unless testing `CANARY` or `SHADOW`.
- `fallbackReason` is empty.
- The view shows `failureRiskScore`, `failureHorizonDays`, risk drivers, label
  coverage, training data window, and input hash.

![Latest prediction](../../artifacts/ml-frontend-device-prediction-loaded.png)

## 9. Verify Alert Creation

Training a model does not create alerts by itself. Alerts are created after a
deployed model or baseline analytics produces a high-risk event and
alert-service consumes the analytics event.

To verify the alert path:

1. Make sure an alert rule exists for the device or metric that should become
   high risk.
2. Ingest high-risk telemetry or use the deterministic smoke fixture.
3. Run analytics recalculation for the device.
4. Open `Alerts`.
5. Confirm a new `OPEN` alert is visible.
6. Click `Details` and verify source trace fields such as source event,
   anomaly or risk id, rule id, calculation window, and input hash.
7. Acknowledge and resolve the alert to confirm lifecycle actions.

Run the deterministic ML-to-alert smoke:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-alert-incident-docker-smoke.ps1 -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-alert-incident-smoke-summary.json
```

Expected result:

- `ML-to-alert incident E2E` passes.
- The smoke records at least one `OPEN` alert id.
- Notification attempts are created.
- Repeated recalculation deduplicates unresolved alerts.
- After resolve, a repeated high-risk condition can reopen a new alert.

![Alerts desktop](../../artifacts/screenshots/ml-full-verification/alerts-desktop.png)

Mobile screenshots are also captured for the main ML and alert screens:

![ML Settings mobile](../../artifacts/screenshots/ml-full-verification/ml-settings-mobile.png)

![Alerts mobile](../../artifacts/screenshots/ml-full-verification/alerts-mobile.png)

## Verification Commands

Use the narrowest command set for the change being verified.

```powershell
npm run openspec:validate
```

```powershell
cd frontend
npm run typecheck
npm run test
npm run build
```

```powershell
cd ml-service
.\.venv\Scripts\python.exe -m unittest discover -s app/tests
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-failure-prediction-docker-smoke.ps1 -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-production-smoke-summary.json
powershell -ExecutionPolicy Bypass -File scripts/ml-alert-incident-docker-smoke.ps1 -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-alert-incident-smoke-summary.json
```

For release evidence, run the full gate:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-full-docker-verification.ps1 -StartStack -CiMode -TimeoutSeconds 1200 -SummaryPath artifacts/ml-full-verification-summary.json
```

## Pass Criteria

The frontend and ML-to-alert workflow passes when:

- Dataset creation is available only after a fresh backend preview.
- Failure-prediction dataset, labels, feature snapshot, and training all use
  the same positive horizon.
- A training run can be queued from the frontend.
- A successful run registers a candidate model with artifact metadata.
- Approval gates block weak failure-prediction candidates and allow valid
  candidates.
- Deployment policy resolution selects the approved model.
- Latest prediction uses `TRAINED_MODEL` without fallback for the deployed
  model.
- High-risk analytics creates an `OPEN` alert with source traceability.
- Alert acknowledge, resolve, dedupe, reopen, and notification attempt evidence
  are present.
