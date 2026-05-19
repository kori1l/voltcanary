# Simulator Service

`simulator-service` owns YAML simulation configuration, simulation runs and run history. It is a telemetry producer, not an ingestion service.

Telemetry flow:

```text
simulator-service -> ingestion-service -> telemetry.raw.v1 -> telemetry-service -> TimescaleDB
```

The service never writes directly to TimescaleDB and never publishes `telemetry.raw.v1` directly. HTTP mode uses a data source ingestion token issued by `device-service`. MQTT mode publishes to the internal broker, where `ingestion-service` consumes the message and still owns source resolution and canonical event publishing.

MVP endpoints:

- `GET /simulation/devices/{deviceId}/config`
- `PUT /simulation/devices/{deviceId}/config`
- `POST /simulation/devices/{deviceId}/config/validate`
- `POST /simulation/devices/{deviceId}/start`
- `POST /simulation/devices/{deviceId}/stop`
- `GET /simulation/devices/{deviceId}/status`
- `GET /simulation/devices/{deviceId}/runs`
- `GET /simulation/runs/{runId}`
- `GET /simulation/devices/{deviceId}/config/versions`
