# vehicle-monitoring-console Specification

## Purpose
Define authorized fleet monitoring, observed journeys, recorded-sample playback,
CSV export, owner zones and configurable vehicle emulation.
## Requirements
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

### Requirement: Fleet discovery excludes archived inventory from the display cap
The console SHALL page authorized vehicle inventory and exclude DECOMMISSIONED
vehicles before applying its 100-vehicle display limit. It SHALL preserve source
authorization, show truncation when additional eligible vehicles exist, and
report discovery errors without presenting a partial list as complete.

#### Scenario: New real vehicle follows archived test devices
- **WHEN** more than 100 archived vehicles precede a newly created accessible vehicle
- **THEN** the new vehicle is discovered and its selected source can participate in the ordinary authorized fleet position batch

#### Scenario: Fleet exceeds the display limit
- **WHEN** more than 100 non-decommissioned vehicles are discovered
- **THEN** at most 100 are shown and the console displays its truncation indicator

