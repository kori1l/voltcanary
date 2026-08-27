# First Telemetry Flow

## Scenario

1. User registers.
2. User creates device.
3. User sends telemetry.
4. Ingestion publishes `telemetry.raw.v1`.
5. Telemetry consumes event.
6. Telemetry stores reading.
7. Frontend displays chart.

## Definition of Done

- All services start.
- Each service has at least one smoke test.
- `EventEnvelope` exists.
- `TelemetryReceivedEvent` exists.
- `TelemetryValidatedEvent` exists.
- First telemetry flow is documented.
