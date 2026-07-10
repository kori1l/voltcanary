# Design: ML Predictive Analytics Upgrade

## Current State

ML-service exposes:

- `GET /health`
- `POST /inference`
- `GET /models`
- `POST /train`
- `GET /runs/{run_id}`

The current inference implementation returns a constant result:

- `modelVersion=mock-model-v1`
- `anomalyScore=0.12`
- `degradationScore=0.08`
- `riskLevel=LOW`
- `confidence=0.5`

Analytics-service already calls ML through `MlClient`, passes `excludedIntervals`, and persists derived analytics entities such as `PredictionResult`, `RiskScore`, `Anomaly`, and `Recommendation`.

## Target Architecture

Use ML-service as the predictive scoring boundary and analytics-service as the orchestration/persistence boundary.

```text
telemetry.validated.v1
  -> analytics-service job
  -> feature/baseline window
  -> ML inference request
  -> prediction result
  -> risk/recommendation/anomaly derivation
  -> analytics events / API responses
```

ML-service should be stateless for the first iteration except for in-memory or file-backed model registry metadata if needed. Analytics-service remains the durable source for historical predictions and user-visible analytics state.

## Contract Shape

Keep existing field names and add optional fields in a backward-compatible way.

Request:

- `deviceId`
- `timestamp`
- `metrics`
- `baseline` optional object
- `recentWindow` optional aggregate object
- `excludedIntervals`
- `modelPreference` optional object

Response:

- `deviceId`
- `modelName`
- `modelVersion`
- `modelType`
- `modelStatus`
- `predictionStatus`
- `anomalyScore`
- `degradationScore`
- `riskLevel`
- `confidence`
- `signals`
- `explanations`
- `recommendedActions`
- `inputHash`

`predictionStatus` values:

- `MODEL`
- `BASELINE_HEURISTIC`
- `FALLBACK`
- `INSUFFICIENT_DATA`

## Scoring Policy

First iteration scoring should be deterministic and explainable:

- Compare incoming metrics against baseline means/ranges when available.
- Detect absolute unsafe values for known metrics such as power, voltage, current, energy growth, RSSI, and relay state.
- Produce `INSUFFICIENT_DATA` when there is no useful metric input.
- Lower confidence when baseline context is missing or maintenance exclusions remove too much of the window.
- Include signal-level explanations so UI and alerts can explain why the score changed.

Do not use opaque random values.

## Analytics-Service Integration

Analytics-service should:

- build or reuse feature sets before calling ML;
- pass maintenance `excludedIntervals` to ML;
- persist every successful prediction with model metadata and input hash;
- continue rule-based analytics when ML returns no prediction;
- mark ML unavailable/fallback without failing the entire analytics job;
- expose enough prediction metadata through analytics APIs for frontend explanation.

## Failure Handling

- ML-service validation errors should be explicit `400` responses.
- ML-service runtime failures should not crash analytics-service jobs.
- Analytics-service should count ML failures through existing metrics and persist deterministic fallback/rule-based outputs when possible.
- Public user APIs must not expose stack traces or internal ML URLs.

## Verification Strategy

- Python tests cover request validation, deterministic scoring, insufficient-data behavior, and model registry output.
- Java tests cover ML success, ML unavailable fallback, persistence of prediction metadata, and maintenance exclusion propagation.
- Manual verification confirms that simulator-generated normal data produces low risk and anomaly profiles produce elevated risk with readable explanations.
