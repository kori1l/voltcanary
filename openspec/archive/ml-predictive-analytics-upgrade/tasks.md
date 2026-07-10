# Tasks: ML Predictive Analytics Upgrade

## 1. Contract Documentation

- [x] 1.1 Expand `docs/api/ml-api.md` with request/response examples, status values, failure responses, and compatibility notes.
- [x] 1.2 Expand `docs/api/analytics-api.md` with prediction/risk metadata semantics and ML fallback behavior.
- [x] 1.3 Update `docs/flows/system-verification-flow.md` with an ML predictive analytics verification scenario.

## 2. ML-Service Contract

- [x] 2.1 Extend `InferenceRequest` with optional baseline, recent-window, and model-preference fields.
- [x] 2.2 Extend `InferenceResponse` with model name/type/status, prediction status, signals, recommended actions, and input hash.
- [x] 2.3 Keep existing aliases such as `deviceId`, `modelVersion`, `anomalyScore`, `degradationScore`, and `riskLevel`.
- [x] 2.4 Add validation for empty/invalid metric payloads and non-finite numeric values.

## 3. ML-Service Scoring

- [x] 3.1 Replace fixed mock scores with a deterministic baseline heuristic model.
- [x] 3.2 Add signal-level scoring for known electrical metrics.
- [x] 3.3 Add confidence calculation based on input completeness, baseline availability, and maintenance exclusions.
- [x] 3.4 Return `INSUFFICIENT_DATA` when the request cannot support a meaningful prediction.
- [x] 3.5 Update `/models` to report the active baseline heuristic model and readiness state.

## 4. Analytics-Service Integration

- [x] 4.1 Extend `MlInferenceRequest` and `MlInferenceResponse` Java records for the new optional fields.
- [x] 4.2 Send available feature/baseline context from analytics-service to ML-service.
- [x] 4.3 Persist prediction results with model name, model version, prediction status, scores, confidence, explanations, and input hash.
- [x] 4.4 Preserve existing job success behavior when ML-service is unavailable.
- [x] 4.5 Ensure risk/recommendation generation can include ML explanation signals when present.

## 5. Tests

- [x] 5.1 Add Python API tests for low-risk normal telemetry.
- [x] 5.2 Add Python API tests for high-risk anomalous telemetry.
- [x] 5.3 Add Python API tests for insufficient data and invalid numeric values.
- [x] 5.4 Add Java tests for ML fallback when `MlClient` returns null.
- [x] 5.5 Add Java tests that maintenance `excludedIntervals` reach the ML request.
- [x] 5.6 Add Java persistence tests for prediction result metadata.

## 6. Verification

- [x] 6.1 Run `python -m unittest discover -s app/tests` in `ml-service`.
- [x] 6.2 Run targeted analytics-service Maven tests.
- [x] 6.3 Run full backend tests if targeted tests pass.
- [x] 6.4 Record remaining manual verification gaps in the final implementation notes.
