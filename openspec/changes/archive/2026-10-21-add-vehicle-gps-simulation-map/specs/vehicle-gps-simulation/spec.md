## ADDED Requirements

### Requirement: Vehicle uses existing device and source lifecycle
The system SHALL expose device type VEHICLE and SHALL represent its virtual GPS sensor as a SIMULATOR/REST source configured with VEHICLE_GPS. It SHALL reuse existing create/checkpoint/credential/config/start/stop workflows, keep locationId optional and organizational, and SHALL NOT require a new Vehicle aggregate or hidden Sensor records. Profile/source/device compatibility SHALL be checked on save and start.

#### Scenario: Create an emulated automobile
- **WHEN** a user creates a VEHICLE with simulation selected
- **THEN** the wizard proposes VEHICLE_GPS/CITY_LOOP/HTTP and creates one device, source and saved configuration through existing stages; no run starts without an explicit start action or selected start-immediately option.

#### Scenario: Partial setup failure
- **GIVEN** the automobile and source exist but config save fails
- **WHEN** setup is retried
- **THEN** checkpoint reconciliation reuses those identities and does not expose the source token in YAML, logs or browser draft storage.

#### Scenario: Incompatible saved target
- **WHEN** VEHICLE_GPS is saved or started for an appliance or incompatible source
- **THEN** validation rejects it with a concrete error before starting a run.

### Requirement: Minimal profile-scoped GPS configuration
VEHICLE_GPS SHALL provide one CITY_LOOP template with default interval 5s, duration 30m and physical speed 36 km/h. YAML schemaVersion 1 SHALL add gps.routeId/gps.speedKmh and generated metric definitions only for latitude, longitude, speedKmh and headingDeg in this profile. It SHALL require NORMAL/HTTP, schedule.speed=1, startDelay=0, empty anomalies, interval integer 1–30s, duration from interval through 24h and finite physical speed 1–120 km/h. Existing appliance configuration SHALL remain compatible.

#### Scenario: Valid GPS template
- **WHEN** the user loads CITY_LOOP for VEHICLE_GPS
- **THEN** all four correlated generated metrics and valid defaults appear as an unsaved draft; loading does not save or start it.

#### Scenario: Invalid YAML or incompatible preset
- **WHEN** routeId is unknown, speed is out of range, one generated coordinate is omitted or an appliance anomaly is supplied
- **THEN** validation identifies the relevant field and does not silently replace the configuration with an appliance template.

#### Scenario: Existing appliance
- **WHEN** an existing FRIDGE YAML is validated and started
- **THEN** no GPS fields are required and current presets and telemetry remain supported.

### Requirement: Correlated route generation through the ingestion boundary
A focused VehicleGpsGenerator SHALL generate one atomic position per emitted tick using a local closed CITY_LOOP, cumulative geographic segment distances, monotonic elapsed time and actual UTC measurement timestamps. The first point SHALL be the route origin; subsequent points SHALL traverse at configured physical speed, loop continuously and expose a normalized current-segment course. The generator SHALL use existing HTTP ingestion/token and run lease/lifecycle semantics without direct telemetry database writes or Kafka publishing.

#### Scenario: Deterministic movement and loop
- **GIVEN** fixed clock inputs and physical speed
- **WHEN** several ticks cross a corner and the end of the closed route
- **THEN** positions move the expected distance, heading follows the active segment, coordinates share one timestamp and the loop does not jump between disconnected endpoints.

#### Scenario: Delayed scheduler
- **WHEN** a tick is delayed
- **THEN** elapsed distance reflects the run clock and no burst of duplicate timestamps or future-dated accelerated measurements is emitted.

#### Scenario: Stop and fresh start
- **WHEN** the run is stopped and a new run is explicitly started
- **THEN** generation obeys existing stop semantics and the new run begins at the route origin without promising automatic continuation after process restart.

#### Scenario: Sender failure
- **WHEN** ingestion rejects the credential or is unavailable
- **THEN** existing run failure reporting is used and the simulator does not bypass ingestion to persist positions.
