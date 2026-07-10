# ML Predictive Analytics

## Purpose

Define the first production-grade predictive analytics contract between
ML-service and analytics-service.

## Requirements

### Requirement: Deterministic Predictive Inference

ML-service SHALL return prediction scores derived from the request input instead of fixed mock values.

#### Scenario: Normal telemetry returns low risk

- **GIVEN** a request contains normal voltage, current, power, and energy metrics
- **AND** the request has no active ML exclusion covering the timestamp
- **WHEN** `/inference` is called
- **THEN** the response SHALL use `predictionStatus=BASELINE_HEURISTIC` or `predictionStatus=MODEL`
- **AND** `riskLevel` SHALL be `LOW` or `MEDIUM`
- **AND** the response SHALL include at least one explanation.

#### Scenario: Anomalous telemetry raises risk

- **GIVEN** a request contains a severe power spike, voltage anomaly, current anomaly, or abnormal energy growth
- **WHEN** `/inference` is called
- **THEN** `anomalyScore` SHALL be higher than normal telemetry for the same model
- **AND** `riskLevel` SHALL be `MEDIUM`, `HIGH`, or `CRITICAL`
- **AND** `signals` SHALL identify the metric or rule that influenced the score.

### Requirement: Insufficient Data Is Explicit

ML-service SHALL distinguish insufficient data from successful low-risk predictions.

#### Scenario: Empty metrics cannot be scored

- **GIVEN** a request contains no useful metrics
- **WHEN** `/inference` is called
- **THEN** the response SHALL use `predictionStatus=INSUFFICIENT_DATA`
- **AND** confidence SHALL be lower than a fully scored prediction
- **AND** explanations SHALL state that there was not enough telemetry input.

### Requirement: Backward-Compatible ML Contract

ML-service SHALL preserve existing response fields used by analytics-service and the frontend.

#### Scenario: Existing clients still parse prediction scores

- **GIVEN** an existing client expects `deviceId`, `modelVersion`, `anomalyScore`, `degradationScore`, `riskLevel`, `confidence`, and `explanations`
- **WHEN** the client calls `/inference`
- **THEN** all existing fields SHALL still be present
- **AND** new fields SHALL be additive.

### Requirement: Model Registry Reports Readiness

ML-service SHALL expose active model metadata through `/models`.

#### Scenario: Baseline heuristic model is available

- **WHEN** `/models` is called
- **THEN** the response SHALL include the active predictive model name, version, type, and status
- **AND** the status SHALL allow analytics-service or operators to distinguish a heuristic model from a trained model.

### Requirement: Analytics-Service Persists Prediction Traceability

Analytics-service SHALL persist model output metadata for successful ML predictions.

#### Scenario: Prediction result can be audited

- **GIVEN** ML-service returns a successful prediction
- **WHEN** analytics-service recalculates analytics for a device
- **THEN** `prediction_results` SHALL store model name, model version, prediction status, scores, confidence, explanation payload, and input hash
- **AND** the stored result SHALL be associated with the device and recalculation window.

### Requirement: Analytics-Service Survives ML Unavailability

Analytics-service SHALL continue analytics processing when ML-service is unavailable.

#### Scenario: ML-service request fails

- **GIVEN** ML-service is unavailable or returns an unexpected error
- **WHEN** analytics-service runs a recalculation job
- **THEN** the job SHALL not fail solely because ML inference failed
- **AND** rule-based anomaly/risk/recommendation logic SHALL still run when enough input exists
- **AND** operational metrics SHALL record the ML failure.

### Requirement: Maintenance Exclusions Reach ML

Analytics-service SHALL propagate maintenance windows marked `excludeFromMl=true` to ML-service.

#### Scenario: Maintenance window overlaps prediction input

- **GIVEN** a device has a maintenance window with `excludeFromMl=true`
- **WHEN** analytics-service requests inference for an overlapping recalculation window
- **THEN** the ML request SHALL include an `excludedIntervals` entry
- **AND** ML confidence or prediction status SHALL reflect reduced usable input when the exclusion removes important data.
