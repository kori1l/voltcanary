## ADDED Requirements

### Requirement: Frontend E2E verifies ML operator workflows

The ML console SHALL have browser-level verification proving operators can run
the ML lifecycle and inspect downstream incidents.

#### Scenario: Operator trains models from the console

- **GIVEN** synthetic datasets and feature snapshots exist for anomaly detection
  and failure prediction
- **WHEN** browser E2E tests use the ML Settings console
- **THEN** the operator SHALL be able to queue anomaly-detection and
  failure-prediction training runs from the UI
- **AND** each run SHALL reach a terminal successful status or show an
  operator-readable failure.

#### Scenario: Operator inspects model-backed prediction

- **GIVEN** a trained model is active for a device
- **WHEN** the operator loads that device in ML Settings or device details
- **THEN** the UI SHALL show prediction status, model version id, model family,
  policy source, fallback reason, confidence, input hash, and available risk
  fields without layout overlap.

#### Scenario: Operator inspects ML-created incident

- **GIVEN** ML output has created an alert for a device
- **WHEN** the operator opens `/alerts` or the device alerts tab
- **THEN** the alert SHALL be visible with status, severity, type, device, and
  source trace fields supported by the API
- **AND** acknowledge and resolve actions SHALL update the UI after reload.
