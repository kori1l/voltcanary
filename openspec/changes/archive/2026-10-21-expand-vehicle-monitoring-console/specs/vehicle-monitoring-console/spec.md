## ADDED Requirements

### Requirement: Authorized fleet monitoring
The system SHALL provide a fleet console using bounded authorized latest-position batches, explicit movement/freshness states, source identity and five-second visible polling.
#### Scenario: Fleet refresh
- **WHEN** an authenticated user opens Vehicles
- **THEN** only accessible VEHICLE devices and their bound source positions are displayed and unauthorized batch targets fail closed

### Requirement: Recorded journeys and playback
The system SHALL read bounded ascending cursor pages with an immutable time cutoff, derive observed trips and allow playback/CSV export without inventing positions across gaps.
#### Scenario: Missing observations
- **WHEN** consecutive samples have a coverage gap or an impossible transition
- **THEN** trip coverage is split and marked incomplete, playback selects actual samples and distance excludes the invalid segment

### Requirement: Owner zones and monitoring settings
The system SHALL persist validated owner monitoring settings and bounded circular zones and derive entry/exit/overspeed evidence from contiguous accepted observations.
#### Scenario: First point within a zone
- **WHEN** the first recorded point is inside a zone
- **THEN** it establishes state without creating an unobserved entry event
#### Scenario: Invalid settings payload
- **WHEN** a settings write contains fractional idle minutes, unknown fields or values outside the configured bounds
- **THEN** it returns400 without changing the last valid owner settings

### Requirement: Configurable vehicle emulation
The system SHALL support bounded custom closed routes with waypoint stops and optional synthetic vehicle metrics through the existing save/start lifecycle.
#### Scenario: Scheduled stop
- **WHEN** the vehicle reaches a waypoint with stopSeconds
- **THEN** accepted telemetry records that waypoint, zero speed and off ignition for the configured dwell before continuing
