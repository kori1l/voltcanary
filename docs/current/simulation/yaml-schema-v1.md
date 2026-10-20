# Simulation YAML Schema v1

Required fields:

- `schemaVersion: 1`
- `simulator.profile`
- `simulator.protocol`
- `target.sourceId`
- `schedule.interval`
- `metrics`

Example:

```yaml
schemaVersion: 1
simulator:
  name: kitchen-fridge-simulator
  profile: FRIDGE
  mode: NORMAL
  protocol: HTTP
target:
  sourceId: "sim-fridge-001"
schedule:
  interval: 5s
  duration: 30m
metrics:
  activePowerW:
    type: cycle
  voltageV:
    type: constant
    value: 230
```

Raw source tokens are not stored in YAML. Local simulator configuration stores
the token separately from the YAML body so templates, versions, logs, and
frontend previews do not expose collector credentials. Target production-like
simulation should use source credential references or injected secrets rather
than persisted raw tokens.

## VEHICLE_GPS extension

Load the full template from `/simulation/templates/VEHICLE_GPS?sourceId=...&preset=CITY_LOOP`.
YAML v1 uses profile VEHICLE_GPS, mode NORMAL, protocol HTTP; schedule interval5s,
duration30m, startDelay0s, speed1.0; `gps: {routeId: CITY_LOOP, speedKmh: 36}`.
Exactly four metrics latitude/longitude/speedKmh/headingDeg use `{type: generated}`.
generated/gps are exclusive to this profile; no anomalies or independent coordinate
values. Interval is integer1–30s, duration between interval and24h, physical speed
finite1–120km/h. CITY_LOOP is a closed local Belgrade contour of straight segments,
without road routing. Timestamp is actual UTC; each new run starts at the origin.

## Configurable vehicle routes

Optional gps.waypoints and synthetic vehicle fields extend VEHICLE_GPS without changing the original CITY_LOOP template. See [vehicle route contract](../flows/vehicle-monitoring-verification.md#simulation-extension) for bounds/defaults, dwell, fuel exhaustion and reset semantics.
