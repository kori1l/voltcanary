## 1. Verification Runbook And Evidence

- [x] 1.1 Add `docs/flows/ml-full-verification.md` with the full test matrix,
  data setup, pass criteria, and triage guidance.
- [x] 1.2 Define `artifacts/ml-full-verification-summary.json` schema for
  machine-readable run evidence.
- [x] 1.3 Add a human-readable report template for full ML verification runs.
- [x] 1.4 Document which checks are mandatory for local release evidence,
  pull-request CI, and manual exploratory validation.

## 2. Test Data And Fixtures

- [x] 2.1 Create deterministic synthetic telemetry generators for
  `normal-washer`, `spiking-washer`, `degrading-washer`, and
  `maintenance-washer`.
- [x] 2.2 Ensure fixtures create user, account, devices, source tokens,
  telemetry, maintenance windows, and corrective maintenance outcomes through
  public or documented internal APIs.
- [x] 2.3 Add fixture cleanup or namespace isolation so repeated runs do not
  collide.

## 3. ML-Service Coverage

- [x] 3.1 Verify baseline heuristic normal, anomalous, insufficient-data, and
  maintenance-exclusion inference.
- [x] 3.2 Verify anomaly-detection training and trained-model inference.
- [x] 3.3 Verify failure-prediction training, metrics, approval metadata, and
  trained-model inference.
- [x] 3.4 Verify missing artifact, hash mismatch, feature mismatch, missing
  online features, auth rejection, request size limit, idempotent retry,
  cancellation, partial artifact cleanup, and serving cache behavior.

## 4. Backend Integration Coverage

- [x] 4.1 Verify dataset preview/create, horizon labels, feature snapshots, and
  training lifecycle in analytics-service tests.
- [x] 4.2 Verify approval gates, model registration, deployment policy
  resolution, shadow/canary/active/fallback traceability, and ML monitoring
  metrics.
- [x] 4.3 Verify tenant isolation for ML datasets, labels, snapshots, training
  runs, models, policies, predictions, and alerts.
- [x] 4.4 Verify lifecycle impact, archive, delete, and cleanup behavior for
  ML lineage.

## 5. Alert/Incident Coverage

- [x] 5.1 Add tests proving high anomaly ML output creates an `OPEN` alert.
- [x] 5.2 Add tests proving high failure-risk ML output creates an `OPEN`
  alert.
- [x] 5.3 Add tests proving low-risk predictions do not create alerts.
- [x] 5.4 Add tests proving maintenance-suppressed ML outputs do not create
  new user-visible alerts and record suppression evidence where supported.
- [x] 5.5 Add tests for ML alert deduplication, reopen-after-resolve,
  acknowledge, resolve, filters, summary counts, and notification attempts.
- [x] 5.6 Ensure alert evidence preserves source trace to ML prediction/risk
  inputs without exposing secrets.

## 6. Docker E2E Automation

- [x] 6.1 Keep `scripts/ml-failure-prediction-docker-smoke.ps1` as the
  supervised failure happy-path gate.
- [x] 6.2 Add `scripts/ml-anomaly-detection-docker-smoke.ps1` for anomaly
  training, deployment, live scoring, and alert generation.
- [x] 6.3 Add `scripts/ml-alert-incident-docker-smoke.ps1` for prediction to
  alert lifecycle, deduplication, and notification attempts.
- [x] 6.4 Add `scripts/ml-full-docker-verification.ps1` to orchestrate all ML
  smoke scenarios and write one summary.
- [x] 6.5 Add resilience checks for ML-service outage, analytics restart,
  alert-service restart, artifact outage, and Redpanda replay.

## 7. Frontend E2E Coverage

- [x] 7.1 Add browser E2E checks for ML Settings workflow modes, dataset
  workbench, labels, feature snapshots, training, models, policies,
  monitoring, and latest prediction.
- [x] 7.2 Add browser E2E checks proving a training run can be queued from the
  UI for both anomaly detection and failure prediction.
- [x] 7.3 Add browser E2E checks proving ML-created alerts appear in `/alerts`
  and device alert tabs, and can be acknowledged and resolved.
- [x] 7.4 Add UI negative-path checks for stale preview, invalid ranges,
  mismatched family/horizon, missing snapshots, and backend errors.
- [x] 7.5 Capture desktop and mobile screenshots for ML and alert workflows.

## 8. CI And Validation

- [x] 8.1 Decide which full-gate subset runs on every ML-affecting pull request
  and which subset remains manual release evidence.
- [x] 8.2 Wire CI to upload sanitized summary and screenshots for failed and
  successful ML verification runs.
- [x] 8.3 Run backend analytics/alert tests, ML-service tests, frontend
  typecheck/tests/build, Docker smoke scripts, and `npm run openspec:validate`.
- [x] 8.4 Archive this OpenSpec change only after the full ML verification
  matrix is implemented and has passing evidence.
