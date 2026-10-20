## MODIFIED Requirements

### Requirement: Minimal profile-scoped GPS configuration
VEHICLE_GPS SHALL retain CITY_LOOP with defaults5s/30m/36kmh and the original four generated GPS metrics. YAML v1 SHALL additionally allow a bounded custom closed waypoint route with integer dwell and optional generated ignition/fuelPercent/odometerKm. NORMAL/HTTP, schedule.speed=1, zero start delay, empty anomalies and existing interval/duration bounds SHALL remain required. Appliance YAML SHALL remain compatible.
#### Scenario: Existing GPS configuration
- **WHEN** an original four-metric CITY_LOOP configuration is saved
- **THEN** it remains valid and emits the original position readings
#### Scenario: Custom route with vehicle fields
- **WHEN** optional custom waypoints and vehicleMetrics are configured
- **THEN** route/stop/numeric bounds and the seven generated metric definitions are validated before save/start

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
