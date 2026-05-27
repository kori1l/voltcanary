# Telemetry Ingestion

`ingestion-service` is the unified telemetry boundary. HTTP requests and MQTT messages must map to `IncomingTelemetryMessage` and call `TelemetryIngestionPipeline`.

The pipeline validates required fields, timestamp skew/backfill limits, supported metric names, metric value ranges, source and metric name lengths, and batch size. It resolves the source through `device-service`, checks that the source token hash matches the submitted token, verifies that the token belongs to the submitted `sourceId` and `deviceId`, checks source status/protocol, applies idempotency, builds `TelemetryReceivedEvent`, waits for a broker acknowledgement, and publishes to `telemetry.raw.v1`.

Telemetry-service repeats defensive validation before persistence. It treats ingestion as the primary external trust boundary, but still rejects malformed events, unsupported metrics, out-of-window timestamps, and out-of-range values before writing TimescaleDB rows.

Batch ingestion uses partial-success semantics. The response includes accepted, duplicate, and rejected counts plus per-reading status entries. Kafka publish failures return `503`; the idempotency key is released so collectors can retry the same reading.

Realtime ingestion enforces rate limits by source, device, account, and client
IP. When Kafka publish failures occur, the service opens a short backpressure
window and returns `503` before accepting more readings. The MVP keeps
operational history in bounded in-memory audit logs and Micrometer metrics; it
does not store telemetry time-series data in ingestion.

Historical imports use `/ingestion/imports/readings` and
`/ingestion/imports/readings/batch`. Import endpoints keep the same validation
and source-token checks but allow older timestamps than realtime ingestion.
