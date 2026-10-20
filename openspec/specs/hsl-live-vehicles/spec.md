# hsl-live-vehicles Specification

## Purpose
TBD - created by archiving change connect-hsl-live-vehicles. Update Purpose after archive.
## Requirements
### Requirement: Real HSL device identity
The system SHALL ingest configured public HSL trams as owner-bound VEHICLE devices with API/REST sources and stable topic operator plus vehicle identifiers, without simulator runs or fabricated vehicle metrics.

#### Scenario: Vehicle changes route
- **WHEN** an allowed tram publishes on a different route topic
- **THEN** its existing device/source receives the new GPS observation

### Requirement: Bounded observed GPS ingestion
The connector SHALL validate GPS coordinates and source timestamps, convert speed units and heading, reject retained, stale, future, duplicate or out-of-order observations, and retain at most one pending latest position per allowed vehicle with five-second forwarding intervals.

#### Scenario: Missing GPS or optional measurement
- **WHEN** a VP payload lacks GPS coordinates or reports a non-GPS location
- **THEN** it is skipped without inventing a position
- **AND** a valid GPS payload lacking speed or heading omits those metrics

#### Scenario: Feed or ingestion outage
- **WHEN** MQTT or ingestion becomes unavailable
- **THEN** the connector reconnects or retries bounded latest fresh samples without updating observation timestamps
- **AND** existing telemetry freshness eventually becomes stale

### Requirement: Secure repeatable local deployment
The system SHALL provide opt-in Compose deployment and idempotent provisioning with verified MQTT TLS, owner authenticated setup, per-source bearer ingestion and ignored private credential configuration.

#### Scenario: Restart and repeat setup
- **WHEN** the connector restarts or provisioning repeats
- **THEN** the configured vehicle identities and existing dashboard are reused
- **AND** owner passwords, JWTs and source tokens are absent from tracked files and logs

### Requirement: Real transport map visibility
The system SHALL publish a Helsinki HSL dashboard for the selected real vehicles and expose their accepted positions and recorded history through existing vehicle views.

#### Scenario: Public tram moves
- **WHEN** successive real GPS observations are accepted
- **THEN** the device timestamp and map position reflect those observations with HSL attribution

### Requirement: Shared fleet map widget
The vehicle-map widget SHALL support a fixed set of 1-20 vehicles with an explicit source per device and render their latest positions on one retained map with marker selection and fit-all control. Single-device sourceId widgets SHALL remain compatible. Fleet reads SHALL authorize the resolved scope and validate every source binding before reading positions, filter source identifiers from shared definitions to the permitted devices, and bypass stale query caches.

#### Scenario: All connected Helsinki trams
- **WHEN** the Helsinki dashboard is opened
- **THEN** one map shows all configured trams with available positions and their freshness and timestamps

#### Scenario: Runtime or viewer scope narrows the fleet
- **WHEN** only some fixed vehicles remain permitted in the resolved scope
- **THEN** only those vehicles and their source identifiers are read and exposed

