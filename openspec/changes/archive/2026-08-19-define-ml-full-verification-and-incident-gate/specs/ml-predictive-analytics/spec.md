## ADDED Requirements

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
