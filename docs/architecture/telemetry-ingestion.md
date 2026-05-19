# Telemetry Ingestion

`ingestion-service` is the unified telemetry boundary. HTTP requests and future MQTT messages must map to `IncomingTelemetryMessage` and call `TelemetryIngestionPipeline`.

The pipeline validates required fields, resolves the source, checks data source status/protocol, applies idempotency, builds `TelemetryReceivedEvent`, and publishes to `telemetry.raw.v1`.
