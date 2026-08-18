## ADDED Requirements

### Requirement: Offline and online feature parity is verified

The system SHALL verify that offline feature snapshots and online inference
feature vectors produce equivalent required features for the same model family,
template, horizon, device, metric, and prediction timestamp.

#### Scenario: Online vector matches offline snapshot row

- **GIVEN** a feature snapshot row was generated for a device, metric,
  timestamp, model family, template, and horizon
- **WHEN** analytics-service generates the online feature vector for live
  inference at the same prediction point
- **THEN** required feature columns and contract metadata SHALL match the
  offline row
- **AND** parity tests SHALL prove the expected values for the supported
  template.

#### Scenario: Feature generator version differs

- **GIVEN** a selected model requires one feature generator or template version
- **WHEN** live inference produces features from another incompatible generator
  or template version
- **THEN** the system SHALL not score the model silently
- **AND** SHALL return or persist an explicit feature-contract mismatch status.

#### Scenario: Failure horizon differs

- **GIVEN** a failure-prediction model was trained for a specific horizon
- **WHEN** online features are generated for a different or missing horizon
- **THEN** the system SHALL treat the features as incompatible
- **AND** fallback or unavailable traceability SHALL include the required and
  generated horizon values.
