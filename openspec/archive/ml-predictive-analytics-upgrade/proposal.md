# Change Proposal: ML Predictive Analytics Upgrade

## Why

The current ML service is a stub: `/inference` returns a fixed `mock-model-v1` response, `/models` returns a static model, and `/train` only reports that training is not implemented. Analytics-service already has concepts for feature sets, predictions, risk scores, recommendations, maintenance exclusions, outbox publishing, and job recalculation, but the ML boundary does not yet produce meaningful predictive analytics.

This creates a product gap: VoltCanary claims predictive IoT analytics, but the ML path cannot explain real anomaly/degradation signals, cannot report model readiness, and cannot separate deterministic fallback behavior from model-based predictions.

## What Changes

- Define a real first iteration ML inference contract for anomaly score, degradation score, risk level, confidence, explanations, model metadata, and fallback status.
- Replace fixed mock inference with a deterministic baseline model that derives scores from telemetry metrics and recent baseline context supplied by analytics-service.
- Add explicit model readiness and model registry semantics so analytics-service can know whether it is using a trained model, a baseline heuristic, or a fallback.
- Extend analytics-service integration so prediction results are persisted with traceable model metadata and can contribute to risk/recommendation output.
- Preserve existing route compatibility while documenting the target contract in `docs/api/ml-api.md` and `docs/api/analytics-api.md`.
- Add test coverage for ML inference, maintenance exclusions, unavailable ML fallback, and persisted prediction metadata.

## Non-Goals

- Do not introduce online learning or heavy ML infrastructure in this iteration.
- Do not require external model stores, GPU runtimes, or third-party ML SaaS.
- Do not replace rule-based anomaly detection or alert rules.
- Do not make analytics-service synchronously dependent on ML-service availability for telemetry ingestion.
- Do not redesign the frontend in this change.

## Impacted Areas

- `ml-service/app/api/*`
- `ml-service/app/schemas/*`
- `ml-service/app/services/*`
- `ml-service/app/models/*`
- `ml-service/app/tests/*`
- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/ml/*`
- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/service/*`
- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/domain/*`
- `docs/api/ml-api.md`
- `docs/api/analytics-api.md`
- `docs/flows/system-verification-flow.md`

## Open Questions

- Should analytics-service send raw recent telemetry windows to ML-service, or only aggregated feature sets for this first iteration?
- Should the first deterministic model live only in ML-service, or should analytics-service keep a local emergency fallback with identical risk thresholds?
- Should model training produce persisted artifacts in this iteration, or should it only compute and expose per-device baseline summaries?
