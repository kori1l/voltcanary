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

### Requirement: ML verification covers every serving path

The system SHALL provide repeatable verification for every supported ML serving
path: baseline heuristic, trained anomaly detection, and trained failure
prediction.

#### Scenario: Release gate verifies all model families

- **GIVEN** a release or ML-affecting change is being validated
- **WHEN** the full ML verification gate runs
- **THEN** baseline heuristic scoring SHALL be verified with normal, anomalous,
  insufficient, and maintenance-excluded telemetry
- **AND** trained anomaly-detection scoring SHALL be verified with a model
  trained from generated feature snapshots
- **AND** trained failure-prediction scoring SHALL be verified with a model
  trained from horizon labels
- **AND** each successful trained-model prediction SHALL prove which
  `modelVersionId` was used.

#### Scenario: Controlled model rejection is verified

- **GIVEN** a selected model cannot be loaded, lacks online features, or has a
  feature contract mismatch
- **WHEN** live recalculation runs
- **THEN** prediction traceability SHALL store a controlled unavailable,
  features-unavailable, or schema-mismatch status
- **AND** fallback SHALL be used only when the resolved policy permits it
- **AND** monitoring metrics SHALL expose the degraded path.

### Requirement: ML predictions drive alert incidents

High-risk ML outputs SHALL be verified through the downstream alert/incident
workflow, not only through prediction API responses.

#### Scenario: High anomaly prediction creates an alert

- **GIVEN** a device has telemetry that produces a high anomaly or high
  rule-compatible ML risk
- **WHEN** analytics recalculation publishes the resulting event
- **THEN** alert-service SHALL create an `OPEN` alert for the account and device
- **AND** the alert SHALL include severity, alert type, source event, calculation
  window, and input hash where available.

#### Scenario: High failure-risk prediction creates an alert

- **GIVEN** an active failure-prediction model returns high failure risk for a
  device
- **WHEN** analytics recalculation completes and alert processing catches up
- **THEN** alert-service SHALL create an `OPEN` alert or incident-equivalent
  record for the account and device
- **AND** the alert SHALL preserve traceability to the ML prediction or risk
  evidence supported by the current API.

#### Scenario: Low-risk prediction does not create an alert

- **GIVEN** baseline or trained-model prediction returns low risk
- **WHEN** analytics and alert processing complete
- **THEN** no new user-visible alert SHALL be created for that condition
- **AND** the verification evidence SHALL show the absence of an alert or a
  no-alert inbox result.

#### Scenario: Maintenance-suppressed ML output does not create an alert

- **GIVEN** telemetry occurs during a maintenance window that suppresses ML or
  alerts
- **WHEN** analytics and alert processing complete
- **THEN** no new user-visible ML alert SHALL be opened for the suppressed
  condition
- **AND** suppression or traceability evidence SHALL explain why.

