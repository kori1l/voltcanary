## ADDED Requirements

### Requirement: Mixed live nighttime fleet
The system SHALL accept explicitly configured public HSL buses alongside existing trams, isolate identities by mode, preserve existing bindings, and display the fleet on one map with transport-specific icons. It SHALL retain the existing twenty-device limit and accepted GPS/context validation.

#### Scenario: Active buses join sleeping trams
- **GIVEN** ten configured trams and five currently observed buses on selected nighttime routes
- **WHEN** the owner provisions the buses and the connector receives fresh non-retained GPS
- **THEN** the existing dashboard includes all fifteen vehicles, bus observations and available journey context are persisted with advancing source timestamps, and silent trams remain visibly stale

#### Scenario: Repeated provisioning and identity isolation
- **GIVEN** existing tram and bus bindings, potentially sharing numerical operator/vehicle identities
- **WHEN** provisioning is repeated or a position from another mode arrives
- **THEN** no duplicate device is created and no position is attributed to the other mode's binding
