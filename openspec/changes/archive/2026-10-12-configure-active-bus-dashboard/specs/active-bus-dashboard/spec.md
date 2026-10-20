## ADDED Requirements

### Requirement: Owner quotas and live bus dashboard
The system SHALL enforce a default quota of ten active dashboards and bounded owner-specific overrides. Dashboards SHALL support stored configured metric keys including boolean state charts without changing raw types or inventing missing observations.

#### Scenario: Authorized owner increase
- **GIVEN** the demo owner reaches ten active dashboards
- **WHEN** their quota is explicitly increased to fifty
- **THEN** that owner can create a new dashboard while other owners retain their quota

#### Scenario: Active bus map and states
- **GIVEN** a real bus has fresh positions, speed and door observations
- **WHEN** the dashboard is published
- **THEN** its map displays only that bus above recorded speed and boolean door charts, with responsive layouts and explicit gaps or aggregate ranges
