# Simulator Service

`simulator-service` owns YAML simulation configuration, simulation runs and run history. It is a telemetry producer, not an ingestion service.

Telemetry flow:

```text
simulator-service -> ingestion-service -> telemetry.raw.v1 -> telemetry-service -> TimescaleDB
```

The service never writes directly to TimescaleDB and never publishes `telemetry.raw.v1` directly. HTTP mode uses a data source ingestion token issued by `device-service`. MQTT mode publishes to the internal broker, where `ingestion-service` consumes the message and still owns source resolution and canonical event publishing.

Current endpoints:

- `GET /simulation/devices/{deviceId}/config`
- `PUT /simulation/devices/{deviceId}/config`
- `POST /simulation/devices/{deviceId}/config/validate`
- `POST /simulation/devices/{deviceId}/config/reset-template?profile=FRIDGE&sourceId=sim-fridge-001&preset=NORMAL_OPERATION`
- `POST /simulation/devices/{deviceId}/start`
- `POST /simulation/devices/{deviceId}/stop`
- `GET /simulation/devices/{deviceId}/status`
- `GET /simulation/devices/{deviceId}/runs?page=0&size=25`
- `GET /simulation/runs/{runId}`
- `GET /simulation/devices/{deviceId}/config/versions`
- `GET /simulation/templates`
- `GET /simulation/presets`
- `GET /simulation/templates/{profile}`

Run history returns `{content, page, size, totalElements, totalPages}`. `page`
starts at zero; `size` defaults to 25 and must be 1–100. Invalid bounds return 400. The database limits each page and orders by `startedAt DESC, id DESC`, so
equal start timestamps have deterministic ordering. Concurrent new runs can
shift page positions; refresh to see the current history. The console and setup
reconciliation expose page navigation. Status uses a separate single latest-run
query and does not load history.

`reset-template` returns `{ "yamlConfig": "..." }` without persisting the
configuration. The frontend uses it to replace the editor contents from a
server-side template while keeping save/start as explicit user actions.

Target direction: simulator remains a telemetry producer used for local demos,
verification, and repeatable fixtures. It should not become a second ingestion
or telemetry storage boundary.

## Vehicle GPS producer

VEHICLE_GPS/CITY_LOOP uses existing HTTP sender/token/lifecycle/config versions.
Preset catalog adds compatibleProfiles: CITY_LOOP only supports VEHICLE_GPS;
appliance presets retain their profile list. Template default5s/30m/36km/h.
Save/start validate VEHICLE and SIMULATOR/REST source ownership using
DEVICE_SERVICE_URL and INTERNAL_SERVICE_TOKEN. Save profile/protocol must match
YAML. Generator selection is separate from the run manager. GPS uses monotonic
elapsed time, cumulative geographic distances and fixed-delay scheduling, without
catch-up burst; appliance scheduling is unchanged. GPS stops at configured duration.
Stop/restart follows existing leases/history; restart resets the route.

## Vehicle route extension

The GPS generator snapshots optional custom closed routes/waypoint dwell and vehicle metrics per run. Monotonic elapsed drives movement and stops; existing HTTP ingestion remains authoritative. [Contract and verification](../flows/vehicle-monitoring-verification.md).
