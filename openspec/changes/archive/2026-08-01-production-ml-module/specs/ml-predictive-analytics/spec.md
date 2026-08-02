## MODIFIED Requirements

### Requirement: Model Registry Reports Readiness

ML-service SHALL report available model versions, their readiness, model family,
algorithm, compatible device types, supported metrics, deployment status, and
artifact metadata needed by analytics-service and frontend operators.

#### Scenario: Trained and fallback models are available

- **GIVEN** the registry contains an active trained anomaly model and the baseline heuristic fallback
- **WHEN** a caller requests model readiness
- **THEN** the response includes both model versions
- **AND** each model includes status, family, algorithm, supported metrics, compatible device types, and deployment mode

### Requirement: Analytics-Service Persists Prediction Traceability

Analytics-service SHALL persist enough prediction traceability to audit model
selection, input data, fallback behavior, and operator-visible explanations.

#### Scenario: Prediction is produced by a trained model

- **GIVEN** an active compatible trained model is selected for a device
- **WHEN** analytics recalculates predictions
- **THEN** the prediction result records model id, model version, policy source, dataset lineage when available, feature schema hash, input hash, confidence, scores, signals, explanations, and recommended actions

#### Scenario: Prediction falls back to baseline heuristic

- **GIVEN** no compatible trained model is active or the selected model is unavailable
- **WHEN** analytics recalculates predictions
- **THEN** deterministic rule and baseline heuristic output remains available
- **AND** the prediction result records the fallback reason

