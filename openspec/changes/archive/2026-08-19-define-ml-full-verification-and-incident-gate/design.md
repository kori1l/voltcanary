# Full ML Verification And Incident Gate

## Scope

The verification process must prove that every ML path works in the running
system, not only in isolated tests:

- Baseline heuristic inference for normal, anomalous, insufficient, and
  maintenance-excluded telemetry.
- Anomaly-detection training and serving from generated feature snapshots.
- Supervised failure-prediction training and serving from outcome-derived
  horizon labels.
- Model approval, deployment policy resolution, shadow, canary, active, and
  fallback behavior.
- Prediction traceability, monitoring metrics, and downstream alert/incident
  creation.
- Frontend workflows for data preparation, training, model inspection,
  deployment, latest prediction, and alerts.

## Test Layers

### Layer 1: ML-Service Unit And API Tests

Purpose: prove model-level behavior without Docker orchestration.

Required tests:

| ID | Test | Expected result |
| --- | --- | --- |
| ML-SVC-001 | `/health` and `/health/artifacts` in local and S3 profiles | health is explicit; production-like local artifact storage is rejected |
| ML-SVC-002 | baseline heuristic normal telemetry | low/medium risk, `BASELINE_HEURISTIC`, explanations present |
| ML-SVC-003 | baseline heuristic severe anomaly | higher anomaly/risk scores, `signals` identify metric drivers |
| ML-SVC-004 | insufficient metrics | `INSUFFICIENT_DATA`, low confidence, no crash |
| ML-SVC-005 | maintenance excluded intervals | confidence/status reflect reduced usable input |
| ML-SVC-006 | anomaly training request from valid tabular features | returns verified artifact metadata |
| ML-SVC-007 | failure training request from labeled features | returns failure metrics, approval metadata, and verified artifact metadata |
| ML-SVC-008 | failure training without positive labels | rejected or failed before candidate registration |
| ML-SVC-009 | trained-model inference by `modelVersionId` | loads artifact, returns selected model version id |
| ML-SVC-010 | missing artifact or hash mismatch | explicit `MODEL_UNAVAILABLE` or equivalent, no crash |
| ML-SVC-011 | feature schema/contract mismatch | explicit `FEATURE_SCHEMA_MISMATCH` |
| ML-SVC-012 | missing online features in model-only mode | explicit `FEATURES_UNAVAILABLE` |
| ML-SVC-013 | internal auth rejects unauthorized `/train`, `/inference`, `/models` | 401/403 and no work performed |
| ML-SVC-014 | oversized request body | 413 or structured oversized error before parsing |
| ML-SVC-015 | idempotent training retry | same run/key returns same artifact metadata; conflict is rejected |
| ML-SVC-016 | cancellation before artifact promotion | no candidate-ready manifest promoted |
| ML-SVC-017 | serving cache hit/miss/reload | first request loads and verifies; later request reuses cache; metadata change reloads |

Primary command:

```powershell
cd ml-service
.\.venv\Scripts\python.exe -m unittest discover -s app/tests
```

### Layer 2: Analytics And Alert-Service Tests

Purpose: prove Java service contracts around ML orchestration and incidents.

Required tests:

| ID | Test | Expected result |
| --- | --- | --- |
| ML-BE-001 | dataset workbench preview for anomaly data | returns metrics, windows, readiness, preview hash |
| ML-BE-002 | dataset workbench preview for failure data | returns label readiness, positive/normal/unobservable counts |
| ML-BE-003 | immutable dataset creation | creates `READY` version with accepted rows and immutable row reference |
| ML-BE-004 | horizon label generation | creates positive and normal labels with lineage hash |
| ML-BE-005 | feature snapshot generation for anomaly model | row count, schema/data/config hashes, artifact URI |
| ML-BE-006 | feature snapshot generation for failure model | horizon and model family match dataset/training |
| ML-BE-007 | queue, lease, heartbeat, cancel, retry training run | state transitions match worker reality |
| ML-BE-008 | successful training registers candidate only after smoke inference | candidate has artifact URI, manifest URI, hash, metrics |
| ML-BE-009 | approval gates accept a passing model | status becomes `APPROVED` |
| ML-BE-010 | approval gates reject failed or missing gate metadata | 400 with failed gate names and operator-readable reasons |
| ML-BE-011 | device/device-type/account/system policy resolution | correct policy source is persisted |
| ML-BE-012 | active policy model inference | latest prediction uses selected model version |
| ML-BE-013 | fallback-enabled unavailable model | analytics job completes with fallback trace and metric |
| ML-BE-014 | fallback-disabled unavailable model | explicit unavailable status; no silent baseline substitution |
| ML-BE-015 | shadow policy high-risk model | shadow prediction stored separately; active latest result unchanged |
| ML-BE-016 | canary policy | deterministic inclusion/exclusion is stable |
| ML-BE-017 | maintenance `excludeFromMl` | ML request includes excluded intervals or persisted trace explains suppression |
| ML-BE-018 | tenant isolation | another account cannot access ML datasets, models, policies, predictions, or alerts |
| ML-BE-019 | lifecycle cleanup impact | referenced datasets/snapshots/runs/models/policies are blocked or archived safely |
| ML-BE-020 | ML monitoring metrics | training, inference, fallback, mismatch, cache, shadow/canary, false-alert, lead-time metrics exported |
| ML-BE-021 | high anomaly prediction publishes alert event | alert-service receives analytics event and creates `OPEN` alert |
| ML-BE-022 | high failure-risk prediction publishes alert event | alert-service creates `OPEN` alert with source trace to prediction/risk evidence |
| ML-BE-023 | low-risk prediction does not create incident | alert inbox records no alert or no event is emitted |
| ML-BE-024 | maintenance-suppressed ML risk does not create new alert | suppression is recorded and no user-visible incident is opened |
| ML-BE-025 | alert deduplication for repeated ML condition | same source event/idempotency or unresolved condition does not duplicate alerts |
| ML-BE-026 | resolved ML alert can reopen on new condition | new qualifying event after resolution creates a new `OPEN` alert |
| ML-BE-027 | alert lifecycle from ML incident | ack and resolve work and remain visible in alerts UI/API filters |
| ML-BE-028 | notification attempts for ML alert | durable notification attempt rows are created for enabled channels |

Primary commands:

```powershell
cd backend
mvn -pl services/analytics-service -am test
mvn -pl services/alert-service -am test
```

### Layer 3: Docker E2E Verification

Purpose: prove service wiring, databases, Kafka/Redpanda, Redis, MinIO, gateway,
and running containers.

Required Docker scenarios:

| ID | Test | Expected result |
| --- | --- | --- |
| ML-E2E-001 | start app and observability profiles | required containers running and health endpoints pass |
| ML-E2E-002 | current failure-prediction production smoke | `PASSED`, trained model used in live prediction |
| ML-E2E-003 | anomaly-detection E2E smoke | synthetic anomaly data trains/deploys anomaly model and live prediction uses it |
| ML-E2E-004 | baseline-only E2E smoke | no trained model selected; baseline scoring works and is traceable |
| ML-E2E-005 | model fallback E2E | break selected artifact or schema intentionally; fallback trace and metrics are produced |
| ML-E2E-006 | ML-to-alert incident E2E | trained model or baseline high-risk output creates `OPEN` alert through alert-service |
| ML-E2E-007 | alert lifecycle E2E | created ML alert appears in `/alerts`, can be acknowledged and resolved |
| ML-E2E-008 | notification E2E | enabled non-secret notification channel creates attempt rows for ML alert |
| ML-E2E-009 | frontend E2E against same data | UI shows dataset, training, model, prediction, policy, alert, and monitoring evidence |
| ML-E2E-010 | resilience E2E | stopping ML-service does not fail analytics jobs; fallback/unavailable is persisted |
| ML-E2E-011 | restart E2E | restart analytics, alert-service, and ML-service; no duplicate alerts or training completions |
| ML-E2E-012 | lifecycle cleanup E2E | cleanup preview/block/archive paths pass for referenced ML lineage |

Existing command:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-failure-prediction-docker-smoke.ps1 -StartStack -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-production-smoke-summary.json
```

Expected new scripts:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-anomaly-detection-docker-smoke.ps1 -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-anomaly-smoke-summary.json
powershell -ExecutionPolicy Bypass -File scripts/ml-alert-incident-docker-smoke.ps1 -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-alert-incident-smoke-summary.json
powershell -ExecutionPolicy Bypass -File scripts/ml-full-docker-verification.ps1 -StartStack -CiMode -TimeoutSeconds 1200 -SummaryPath artifacts/ml-full-verification-summary.json
```

### Layer 4: Frontend E2E Verification

Purpose: prove operator workflows through the running React console.

Required browser scenarios:

| ID | Test | Expected result |
| --- | --- | --- |
| ML-UI-001 | login and open `/settings/ml` | ML console renders without runtime errors |
| ML-UI-002 | select workflow mode | anomaly and failure pipelines show correct prerequisites |
| ML-UI-003 | create internal telemetry dataset | workbench preview must be fresh before create; resulting dataset is visible |
| ML-UI-004 | generate feature snapshot | snapshot inventory includes matching family/horizon |
| ML-UI-005 | generate horizon labels | job status and counts visible |
| ML-UI-006 | queue anomaly training | POST is sent from UI; run reaches terminal success |
| ML-UI-007 | queue failure training | POST is sent from UI; run reaches terminal success |
| ML-UI-008 | inspect model cards and approval gates | metrics and backend gate state are readable |
| ML-UI-009 | approve model and create policy | deployable model and device/type selectors submit correct IDs |
| ML-UI-010 | inspect latest device prediction | trained model id, policy source, fallback reason, risk fields, and input hash visible |
| ML-UI-011 | inspect monitoring | ready dataset, active model, fallback, latency, drift, and quality signals visible |
| ML-UI-012 | inspect ML-created alert | `/alerts` and device alert tab show alert with source trace |
| ML-UI-013 | acknowledge and resolve ML alert | status changes remain visible after reload and filters |
| ML-UI-014 | negative UI validation | mismatched family/horizon, stale preview, invalid range, no snapshot, and backend errors are blocked/readable |
| ML-UI-015 | responsive screenshots | ML controls, long IDs, and alert details do not overlap on desktop or mobile |

## End-To-End Test Data

Synthetic data must include at least four deterministic device profiles:

| Profile | Purpose |
| --- | --- |
| `normal-washer` | low-risk baseline and no incident |
| `spiking-washer` | anomaly-detection high-risk alert |
| `degrading-washer` | increasing failure risk before corrective maintenance outcome |
| `maintenance-washer` | high telemetry during maintenance exclusion, no ML incident when suppressed |

Each profile must create:

- account and user;
- device and data source token;
- at least 28 days of telemetry for failure prediction;
- dense short-window anomaly telemetry for anomaly detection;
- optional maintenance windows and outcomes;
- correlation id reused across ingestion, analytics, ML, and alert checks.

## Incident Definition

Until the product introduces a separate incident service, the incident artifact
for ML verification is an alert-service alert:

- `status=OPEN` at creation;
- severity mapped from analytics risk;
- `sourceEventId`, `deviceId`, `accountId`, calculation window, and input hash
  where available;
- source trace to anomaly, risk score, prediction, or rule evidence where the
  current API supports it;
- lifecycle support for acknowledge, resolve, deduplication, and notification
  attempts.

## Pass Criteria

The full ML verification gate passes only when:

- every required service health check passes;
- all model families have at least one successful scoring path;
- trained anomaly and failure models have durable artifacts and traceable model
  ids;
- active trained-model predictions prove the selected `modelVersionId` was
  used or explicitly rejected with a controlled status;
- low-risk data does not create incidents;
- high-risk anomaly or failure outputs create `OPEN` alerts;
- maintenance suppression prevents alert creation when configured;
- repeated events do not duplicate unresolved alerts;
- alert ack/resolve and notification attempt checks pass;
- frontend E2E proves an operator can perform the workflows;
- evidence artifacts contain sanitized IDs, statuses, hashes, and screenshots;
- OpenSpec validation passes.

## Evidence Format

Every full run must write a machine-readable summary and a short human-readable
report under `artifacts/`:

- run id and timestamp;
- service health statuses;
- account, device, dataset, snapshot, training, model, policy, prediction, and
  alert ids;
- model family and serving path for each model;
- prediction status, policy source, fallback reason, model version id, feature
  hash, input hash;
- alert status, severity, type, source event id, and notification attempt ids;
- per-scenario pass/fail/skip status;
- sanitized failure message and failed lifecycle step;
- screenshot paths for frontend checks.

Reports must not include secrets, access tokens, source tokens, full model
artifacts, or raw private telemetry beyond synthetic fixture values.
