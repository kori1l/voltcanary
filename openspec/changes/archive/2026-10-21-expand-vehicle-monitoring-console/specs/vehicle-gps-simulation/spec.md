## MODIFIED Requirements

### Requirement: Minimal profile-scoped GPS configuration
VEHICLE_GPS SHALL retain CITY_LOOP with defaults5s/30m/36kmh and the original four generated GPS metrics. YAML v1 SHALL additionally allow a bounded custom closed waypoint route with integer dwell and optional generated ignition/fuelPercent/odometerKm. NORMAL/HTTP, schedule.speed=1, zero start delay, empty anomalies and existing interval/duration bounds SHALL remain required. Appliance YAML SHALL remain compatible.
#### Scenario: Existing GPS configuration
- **WHEN** an original four-metric CITY_LOOP configuration is saved
- **THEN** it remains valid and emits the original position readings
#### Scenario: Custom route with vehicle fields
- **WHEN** optional custom waypoints and vehicleMetrics are configured
- **THEN** route/stop/numeric bounds and the seven generated metric definitions are validated before save/start
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
A focused VehicleGpsGenerator SHALL generate one atomic position per tick through existing HTTP ingestion with actual timestamps and monotonic elapsed. It SHALL traverse the default or configured closed route, dwell at configured waypoints and generate optional synthetic vehicle fields. Explicit restart SHALL reset route/time/initial vehicle values; fuel exhaustion SHALL stop movement. No direct telemetry database/Kafka writes SHALL be introduced.
#### Scenario: Stop and movement
- **WHEN** elapsed time enters and leaves a configured dwell
- **THEN** accepted samples contain zero speed/off ignition during dwell and configured physical movement afterwards
#### Scenario: Fuel exhaustion
- **WHEN** configured synthetic fuel is depleted
- **THEN** position and odometer stop progressing and ignition/speed become zero
#### Scenario: Existing appliance generation
- **WHEN** a non-GPS simulation runs
- **THEN** its existing generator and scheduling remain unchanged
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
