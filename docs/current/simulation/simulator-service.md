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
- `GET /simulation/devices/{deviceId}/runs`
- `GET /simulation/runs/{runId}`
- `GET /simulation/devices/{deviceId}/config/versions`
- `GET /simulation/templates`
- `GET /simulation/presets`
- `GET /simulation/templates/{profile}`

`reset-template` returns `{ "yamlConfig": "..." }` without persisting the
configuration. The frontend uses it to replace the editor contents from a
server-side template while keeping save/start as explicit user actions.

Target direction: simulator remains a telemetry producer used for local demos,
verification, and repeatable fixtures. It should not become a second ingestion
or telemetry storage boundary.
