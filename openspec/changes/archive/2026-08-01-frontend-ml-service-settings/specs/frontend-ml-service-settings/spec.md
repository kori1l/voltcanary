## ADDED Requirements

### Requirement: ML settings page exposes editable prediction preferences

The frontend SHALL provide an authenticated ML settings workflow where users can
configure ML prediction preferences without editing read-only model metadata.

#### Scenario: User opens ML settings

- **WHEN** an authenticated user opens the ML settings workflow
- **THEN** the UI SHALL display editable controls for ML-assisted prediction enablement, allowed prediction mode, confidence warning threshold, maintenance exclusion behavior, and explanation visibility
- **AND** the UI SHALL display model identity and latest prediction metadata as read-only status.

#### Scenario: User saves valid ML settings

- **WHEN** a user saves valid ML prediction preferences
- **THEN** the frontend SHALL persist the settings through a backend API
- **AND** the UI SHALL show the effective saved values after the backend confirms the update.

#### Scenario: User enters invalid ML settings

- **WHEN** a user enters an invalid confidence threshold or incompatible prediction mode
- **THEN** the frontend SHALL prevent saving or display a controlled validation error
- **AND** no partial browser-only authoritative setting SHALL be applied.

### Requirement: ML settings distinguish account defaults from device overrides

The frontend SHALL make the scope of ML settings explicit.

#### Scenario: Device inherits account defaults

- **GIVEN** a device has no ML settings override
- **WHEN** the user views device ML settings
- **THEN** the UI SHALL show the effective account default values
- **AND** the UI SHALL label those values as inherited.

#### Scenario: Device override is configured

- **GIVEN** a user configures device-specific ML settings
- **WHEN** the settings are saved successfully
- **THEN** the UI SHALL show the override values
- **AND** the UI SHALL allow returning the device to account defaults.

### Requirement: ML status display handles degraded states

The frontend SHALL explain ML-service readiness and prediction degradation without exposing internal URLs or stack traces.

#### Scenario: ML service is ready

- **WHEN** latest prediction metadata reports a ready model and `predictionStatus=MODEL` or `predictionStatus=BASELINE_HEURISTIC`
- **THEN** the UI SHALL display model name, model version, model type, model status, prediction status, confidence, and explanation signals.

#### Scenario: ML service is unavailable

- **WHEN** analytics prediction metadata reports `predictionStatus=UNAVAILABLE`
- **THEN** the UI SHALL show a controlled degraded status
- **AND** the UI SHALL explain that rule-based analytics can continue without exposing internal ML-service details.

#### Scenario: ML prediction has insufficient data

- **WHEN** latest prediction metadata reports `predictionStatus=INSUFFICIENT_DATA`
- **THEN** the UI SHALL show that more telemetry input is required
- **AND** settings controls SHALL remain usable.

### Requirement: ML settings changes are auditable from the UI

The frontend SHALL show enough context for users to understand the impact of a settings change.

#### Scenario: User reviews prediction mode setting

- **WHEN** a user changes the allowed prediction mode
- **THEN** the UI SHALL describe whether analytics may use trained model output, baseline heuristic output, or rule-based fallback only.

#### Scenario: User reviews maintenance exclusion behavior

- **WHEN** a user changes maintenance exclusion behavior
- **THEN** the UI SHALL describe whether maintenance windows suppress ML calls or reduce ML confidence.
