# ML Full Verification

This runbook is the release gate for ML-affecting changes. It combines unit,
service, Docker, browser, monitoring, and alert evidence for the supported ML
serving paths: `BASELINE_HEURISTIC`, `ANOMALY_DETECTION`, and
`FAILURE_PREDICTION`.

OpenSpec source: `openspec/changes/define-ml-full-verification-and-incident-gate`.
Machine summary schema:
`docs/current/flows/ml-full-verification-summary.schema.json`. Human report template:
`docs/current/flows/ml-full-verification-report-template.md`.
Operator frontend walkthrough:
`docs/current/flows/ml-model-training-alert-guide.md`.

## Gate Levels

| Level | Command | Required for |
| --- | --- | --- |
| Local service tests | `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests` | ML-service model/API changes |
| Backend tests | `cd backend; mvn -pl services/analytics-service -am test; mvn -pl services/alert-service -am test` | Analytics, alerting, contracts, lifecycle |
| Frontend tests | `cd frontend; npm run typecheck; npm run test; npm run build` | Console workflow changes |
| Failure smoke | `powershell -ExecutionPolicy Bypass -File scripts/ml-failure-prediction-docker-smoke.ps1 -StartStack -CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-production-smoke-summary.json` | PR gate for supervised failure lifecycle |
| Full Docker gate | `powershell -ExecutionPolicy Bypass -File scripts/ml-full-docker-verification.ps1 -StartStack -CiMode -TimeoutSeconds 1200 -SummaryPath artifacts/ml-full-verification-summary.json` | Release evidence and manual promotion |
| OpenSpec | `npm run openspec:validate` | Every OpenSpec-governed change |

The pull-request CI subset runs OpenSpec, backend, ML-service, frontend
typecheck/build, and the Docker failure-prediction smoke for ML-affecting
paths. The full Docker gate remains manual release evidence because it starts
the whole stack, exercises browser-visible workflows, and intentionally checks
resilience scenarios.

## Test Matrix

| ID | Area | Required evidence |
| --- | --- | --- |
| ML-SVC-001 | Health | `/health` and `/health/artifacts` report explicit local or S3 status; production-like local artifact storage is rejected. |
| ML-SVC-002 | Baseline normal | Low/medium risk, `BASELINE_HEURISTIC`, confidence, explanations, input hash. |
| ML-SVC-003 | Baseline anomaly | Higher anomaly/risk scores and metric driver signals. |
| ML-SVC-004 | Insufficient metrics | `INSUFFICIENT_DATA`, low confidence, no crash. |
| ML-SVC-005 | Maintenance excluded | Reduced usable input is reflected in confidence/status. |
| ML-SVC-006 | Anomaly training | Valid tabular features produce verified artifact metadata. |
| ML-SVC-007 | Failure training | Labeled features produce failure metrics, approval metadata, and verified artifact metadata. |
| ML-SVC-008 | Missing positives | Failure training without positive labels is rejected before candidate registration. |
| ML-SVC-009 | Trained inference | `modelVersionId` loads artifact and is returned in prediction trace. |
| ML-SVC-010 | Missing/hash mismatch | `MODEL_UNAVAILABLE` or equivalent controlled status. |
| ML-SVC-011 | Feature mismatch | `FEATURE_SCHEMA_MISMATCH`. |
| ML-SVC-012 | Missing online features | `FEATURES_UNAVAILABLE`. |
| ML-SVC-013 | Internal auth | Unauthorized `/train`, `/inference`, `/models` reject with 401/403. |
| ML-SVC-014 | Oversized body | 413 or structured oversized error before parsing/training. |
| ML-SVC-015 | Idempotent retry | Same training idempotency key returns same artifact or rejects conflict. |
| ML-SVC-016 | Cancellation | Cancellation before artifact promotion does not publish candidate-ready manifest. |
| ML-SVC-017 | Serving cache | Hit, miss, reload, and corrupt-load behavior are visible. |
| ML-BE-001..020 | Analytics lifecycle | Dataset preview/create, labels, snapshots, training queue/lease/heartbeat/cancel/retry, model approval, policy resolution, active/shadow/canary/fallback, tenant isolation, cleanup, monitoring metrics. |
| ML-BE-021..028 | Alerts/incidents | High anomaly and failure risk create `OPEN` alerts, low risk and maintenance suppression do not, dedupe/reopen/ack/resolve/filter/notification attempts work. |
| ML-E2E-001..012 | Docker | App health, failure smoke, anomaly smoke, baseline smoke, fallback, ML-to-alert, lifecycle, notification, frontend evidence, resilience, restart, cleanup. |
| ML-UI-001..015 | Frontend | `/settings/ml`, workflow mode, dataset, labels, snapshots, anomaly/failure training, model cards, policy, latest prediction, monitoring, ML-created alerts, negative validation, responsive screenshots. |

## Synthetic Data

Every full run uses deterministic, namespaced data from
`scripts/ml-verification-fixtures.ps1`.

| Profile | Purpose |
| --- | --- |
| `normal-washer` | Low-risk baseline and no incident. |
| `spiking-washer` | High anomaly signal and alert creation. |
| `degrading-washer` | Failure-prediction drift before corrective maintenance outcome. |
| `maintenance-washer` | High telemetry during maintenance exclusion/suppression with no user-visible ML incident. |

The fixture namespace is derived from the run id. Repeated runs create new
emails, account-scoped devices, external source ids, and correlation ids, so
they do not collide. Cleanup is by namespace and lifecycle APIs where the
owning service allows delete/archive; generated model artifacts are not
committed.

## Pass Criteria

The gate passes only when:

- gateway, ingestion, analytics-service, alert-service, and ml-service health
  checks pass;
- each model family has at least one successful scoring path;
- anomaly and failure trained models have durable artifacts and traceable model
  ids;
- active trained predictions identify the selected `modelVersionId` or record a
  controlled rejection;
- fallback is used only when deployment policy permits it;
- high-risk anomaly or failure output creates an `OPEN` alert;
- low-risk and maintenance-suppressed output does not open a user-visible
  alert;
- unresolved duplicate alerts are not created, and a resolved condition can
  reopen;
- acknowledge, resolve, filter, and notification attempt evidence is present;
- frontend evidence shows dataset, training, model, policy, prediction,
  monitoring, alert, and responsive views;
- `artifacts/ml-full-verification-summary.json` validates against the schema;
- `npm run openspec:validate` passes.

## Evidence Rules

Write machine evidence to `artifacts/ml-full-verification-summary.json` and a
human report using `docs/current/flows/ml-full-verification-report-template.md`.
Evidence must include sanitized resource ids, statuses, model families, serving
paths, hashes, policy source, fallback reason, alert ids, notification attempt
ids, and screenshot paths.

Evidence must not include access tokens, source tokens, credentials refs,
private telemetry dumps, generated model files, or MinIO object contents.

## Failure Triage

1. If health fails, check `docker compose ps`, the service health endpoint, and
   the matching container log.
2. If telemetry is missing, verify ingestion accepted count and Kafka/telemetry
   consumer health before debugging ML.
3. If labels or snapshots are missing, compare dataset `modelFamily`,
   `horizonDays`, preview hash, and selected metrics.
4. If trained inference returns `MODEL_UNAVAILABLE`,
   `FEATURE_SCHEMA_MISMATCH`, or `FEATURES_UNAVAILABLE`, inspect artifact URI,
   artifact hash, feature schema hash, and online feature payload.
5. If an expected alert is absent, inspect analytics outbox status,
   alert-service inbox status, maintenance suppression, alert rules, and the
   source event id.
6. If frontend evidence fails, rerun the backend/full gate first, then verify
   the API response used by the failing screen.
