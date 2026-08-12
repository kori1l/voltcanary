## ADDED Requirements

### Requirement: Internal telemetry dataset preview is read-only and authoritative

Analytics-service SHALL provide a read-only preview for internal telemetry
dataset creation that uses backend-owned validation logic and does not mutate ML
lifecycle state.

#### Scenario: Operator previews internal telemetry dataset selection

- **GIVEN** the operator has selected accessible devices, metrics, model family,
  quality gates, and a time range
- **WHEN** the operator requests preview
- **THEN** analytics-service SHALL return available metrics, metric coverage,
  candidate windows, selected window status, quality gates, blocking reasons,
  warning reasons, create defaults, preview id or hash, and expiry
- **AND** no dataset version, canonical row, label, feature snapshot, training
  run, model version, or deployment policy SHALL be created.

#### Scenario: Preview discovers metrics from selected devices

- **GIVEN** selected devices expose different telemetry metrics
- **WHEN** preview runs in common-metric mode
- **THEN** the response SHALL return only metrics present on every selected
  device
- **AND** include coverage counts and ratios for each returned metric.

#### Scenario: Preview allows partial coverage explicitly

- **GIVEN** selected devices expose different telemetry metrics
- **WHEN** preview runs in all-available mode
- **THEN** the response SHALL return the metric union with coverage counts,
  ratios, warnings, and blocking reasons where applicable
- **AND** metrics present on zero selected devices SHALL not be selectable.

#### Scenario: Preview recommends candidate windows

- **GIVEN** selected devices and metrics have telemetry over the searched range
- **WHEN** preview evaluates the selection
- **THEN** the response SHALL include ranked candidate windows for best
  coverage, recent stable data, failure-rich data when applicable, and the user
  selected range
- **AND** each candidate SHALL include status, score, quality summary, and
  explanation.

### Requirement: Dataset creation can be bound to a preview contract

Internal telemetry dataset creation SHALL optionally accept preview identity so
the frontend can detect drift between preview and immutable dataset creation.

#### Scenario: Workbench creates dataset from fresh preview

- **GIVEN** preview returned a ready or warning status with a preview hash
- **WHEN** the operator submits dataset creation with the same selection and
  preview hash
- **THEN** analytics-service SHALL revalidate the request
- **AND** create the immutable dataset version only if the selection still
  satisfies dataset creation rules
- **AND** store selected preview summary in the validation report quality
  profile.

#### Scenario: Workbench submits stale preview

- **GIVEN** telemetry, permissions, maintenance exclusions, labels, selected
  metrics, range, or quality gates changed after preview
- **WHEN** the operator submits dataset creation with stale preview identity
- **THEN** analytics-service SHALL reject or conflict the request with a
  structured stale-preview reason
- **AND** the frontend SHALL be able to request a fresh preview.

#### Scenario: Legacy caller creates dataset without preview

- **GIVEN** a non-workbench caller submits the existing internal telemetry
  dataset request without preview fields
- **WHEN** the request is valid
- **THEN** analytics-service SHALL preserve existing create behavior
- **AND** still run authoritative validation and materialization before marking
  the dataset version `READY`.
