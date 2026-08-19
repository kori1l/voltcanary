# ML Full Verification Report

Run id: `<run-id>`
Date: `<UTC timestamp>`
Reviewer: `<name or CI actor>`
Summary JSON: `artifacts/ml-full-verification-summary.json`

## Decision

- Status: `PASSED | FAILED | SKIPPED`
- Release/PR: `<link or identifier>`
- Failed step: `<none or failed step>`
- Follow-up issue: `<none or link>`

## Required Evidence

| Area | Status | Evidence |
| --- | --- | --- |
| Service health | `<status>` | Gateway, ingestion, analytics, alert-service, ml-service health endpoints |
| Baseline heuristic | `<status>` | Normal, anomalous, insufficient-data, maintenance-excluded telemetry |
| Anomaly detection | `<status>` | Dataset, snapshot, training, deployment, trained prediction |
| Failure prediction | `<status>` | Dataset, horizon labels, snapshot, training, approval, deployment, trained prediction |
| Alert incidents | `<status>` | High-risk alert, low-risk no-alert, maintenance suppression, dedupe, reopen |
| Frontend workflow | `<status>` | ML settings, dataset, labels, training, model, policy, prediction, alerts |
| Resilience | `<status>` | ML outage, analytics restart, alert-service restart, artifact outage, replay |

## Resource Trace

| Resource | IDs |
| --- | --- |
| Accounts | `<sanitized ids>` |
| Devices | `<sanitized ids>` |
| Datasets | `<sanitized ids>` |
| Feature snapshots | `<sanitized ids>` |
| Training runs | `<sanitized ids>` |
| Model versions | `<sanitized ids>` |
| Policies | `<sanitized ids>` |
| Predictions | `<sanitized ids>` |
| Alerts | `<sanitized ids>` |
| Notification attempts | `<sanitized ids>` |

## Model Evidence

| Family | Serving path | Status | Model version | Hashes | Prediction trace |
| --- | --- | --- | --- | --- | --- |
| `BASELINE_HEURISTIC` | `<path>` | `<status>` | `<none>` | `<input hash>` | `<risk/status/fallback>` |
| `ANOMALY_DETECTION` | `<path>` | `<status>` | `<id>` | `<artifact/schema/input>` | `<risk/status/fallback>` |
| `FAILURE_PREDICTION` | `<path>` | `<status>` | `<id>` | `<artifact/schema/input>` | `<risk/status/fallback>` |

## Alert Evidence

| Scenario | Status | Alert evidence |
| --- | --- | --- |
| High anomaly | `<status>` | `<alert id, severity, sourceEventId, inputHash>` |
| High failure risk | `<status>` | `<alert id, severity, sourceEventId, modelVersionId, inputHash>` |
| Low risk | `<status>` | `<no-alert evidence>` |
| Maintenance suppression | `<status>` | `<suppression evidence>` |
| Dedupe/reopen/lifecycle | `<status>` | `<alert ids and statuses>` |
| Notification attempts | `<status>` | `<attempt ids and statuses>` |

## Triage Notes

- First failed scenario: `<scenario id>`
- Sanitized failure: `<message>`
- Logs checked: `<container/service and time window>`
- Local changes needed before retry: `<none or notes>`

## Sanitization

- Secrets excluded: `yes`
- Source tokens excluded: `yes`
- Raw private telemetry excluded: `yes`
- Screenshots reviewed for sensitive values: `yes`
