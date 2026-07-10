## ADDED Requirements

### Requirement: Prediction metadata supports frontend ML settings display

Analytics-service SHALL expose prediction metadata in a form that the frontend
can use for ML settings status and explanations.

#### Scenario: Frontend reads latest prediction metadata

- **WHEN** the frontend requests the latest prediction metadata for a device
- **THEN** the response SHALL include model name, model version, prediction status, anomaly score, degradation score, confidence, explanations, recommended actions, signals, and input hash when available
- **AND** missing ML metadata SHALL be represented as controlled fallback values rather than stack traces or internal service URLs.

#### Scenario: Frontend displays ML readiness from metadata

- **WHEN** prediction metadata includes model status and prediction status
- **THEN** the frontend SHALL be able to distinguish trained model output, baseline heuristic output, insufficient data, unavailable ML, and maintenance suppression.

### Requirement: Analytics honors persisted ML prediction preferences

Analytics-service SHALL use persisted ML prediction preferences when deciding
whether and how to request ML predictions.

#### Scenario: ML-assisted predictions are disabled

- **GIVEN** ML-assisted predictions are disabled for the effective device or account settings
- **WHEN** analytics-service recalculates analytics for the device
- **THEN** analytics-service SHALL skip the ML inference request
- **AND** rule-based analytics SHALL continue when enough telemetry input exists.

#### Scenario: Baseline heuristic is disallowed

- **GIVEN** the effective settings disallow baseline heuristic output
- **WHEN** analytics-service requests prediction
- **THEN** the ML request SHALL include model preference metadata that prevents baseline heuristic use when supported by ML-service
- **AND** analytics-service SHALL persist the resulting prediction status.

#### Scenario: Maintenance behavior suppresses ML

- **GIVEN** the effective settings require ML suppression during maintenance exclusions
- **WHEN** a maintenance window with `excludeFromMl=true` overlaps the prediction input
- **THEN** analytics-service SHALL suppress the ML call or persist a maintenance suppression status according to the configured behavior.
