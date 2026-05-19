# MQTT Ingestion

MQTT is implemented as an optional adapter inside `ingestion-service`, not a separate business service.

Topic convention:

```text
voltcanary/sources/{sourceId}/telemetry
```

The adapter parses the topic, maps JSON payload to `IncomingTelemetryMessage(protocol=MQTT)`, and calls the same `TelemetryIngestionPipeline` used by HTTP.

Enable locally:

```bash
INGESTION_MQTT_ENABLED=true docker compose --profile app up ingestion-service simulator-service mosquitto
```

Payload:

```json
{
  "timestamp": "2026-05-19T12:30:00Z",
  "metrics": {
    "activePowerW": 120.5,
    "voltageV": 230.2
  }
}
```
