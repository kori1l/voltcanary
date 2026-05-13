# Data Flow

The main telemetry flow is:

```text
Smart plug -> Ingestion -> Kafka -> Telemetry -> TimescaleDB -> Analytics -> Dashboard
```

In the full platform this expands to:

```text
Smart Plug / Sensor / CSV / API
        |
        v
Ingestion Service
        |
        v
telemetry.raw.v1
        |
        v
Telemetry Service
        |
        v
TimescaleDB
        |
        v
telemetry.validated.v1
        |
        v
Analytics Service
        |
        +----> ML Service
        |
        v
anomaly.detected.v1 / risk-score.changed.v1
        |
        v
Alert Service
        |
        v
Frontend Dashboard
```

## Step 1: Smart Plug Sends Telemetry

The first supported input is REST ingestion. MQTT and CSV import are planned for
later versions.

Example telemetry:

```json
{
  "deviceId": "device-123",
  "sourceId": "plug-001",
  "timestamp": "2026-05-12T12:30:00Z",
  "metrics": {
    "activePowerW": 840.2,
    "voltageV": 230.1,
    "currentA": 3.65,
    "energyWh": 12450.7,
    "relayState": "ON",
    "rssi": -61
  }
}
```

## Step 2: Ingestion Service Validates Input

`ingestion-service` checks that required fields exist:

- `deviceId`
- `sourceId`
- `timestamp`
- `metrics`

It validates timestamps, metric shape, and later device/source existence. It
also owns the first idempotency boundary for duplicate requests.

Idempotency key strategy:

```text
deviceId + sourceId + timestamp + metricName
```

Output event:

- topic: `telemetry.raw.v1`
- key: `deviceId`
- event: `TelemetryReceivedEvent`

Using `deviceId` as the key keeps events for one device in the same Kafka
partition, preserving per-device order as much as the broker can guarantee.

## Step 3: Kafka / Redpanda Decouples Producers and Consumers

Kafka-compatible messaging allows ingestion to remain fast while downstream
processing happens asynchronously.

Benefits:

- ingestion can accept bursts without blocking on analytics
- telemetry processing can scale independently
- analytics can be replayed from events when logic changes
- failures can be isolated with retries and DLQ topics

## Step 4: Telemetry Service Normalizes and Stores Readings

`telemetry-service` consumes `telemetry.raw.v1`.

Responsibilities:

- normalize metric names and units
- reject or flag invalid values
- detect duplicate readings
- detect out-of-order or late readings
- store readings in TimescaleDB
- calculate or schedule aggregates for charts
- publish `telemetry.validated.v1`

Quality flags:

- `OK`
- `DUPLICATE`
- `OUT_OF_ORDER`
- `MISSING_FIELD`
- `INVALID_VALUE`
- `LATE_ARRIVAL`
- `UNKNOWN_METRIC`

TimescaleDB stores:

- raw readings
- quality flags
- latest readings
- aggregates such as 1m, 5m, 1h, and 1d buckets

## Step 5: Analytics Service Calculates Behavior Signals

`analytics-service` consumes `telemetry.validated.v1`.

It calculates:

- baseline by device and metric
- rolling mean and rolling standard deviation
- work cycles
- energy per cycle
- runtime and idle time
- consumption growth
- anomaly score
- degradation score
- risk score
- recommendations

MVP anomaly types:

- power spike
- long cycle
- consumption growth

Risk levels:

| Score | Level |
| ---: | --- |
| 0-30 | LOW |
| 31-60 | MEDIUM |
| 61-80 | HIGH |
| 81-100 | CRITICAL |

Example formula:

```text
riskScore =
  anomalyScore * 0.35
+ trendScore * 0.25
+ cycleDeviationScore * 0.20
+ dataQualityRisk * 0.10
+ offlineRisk * 0.10
```

## Step 6: ML Service Performs Inference

The ML service is separate because model experimentation, data preparation, and
training are better handled in the Python ecosystem.

`analytics-service` calls `ml-service` through REST for:

- model inference
- training runs
- model registry queries
- future model quality metrics

The ML response includes:

- `modelVersion`
- `anomalyScore`
- `degradationScore`
- `riskLevel`
- `confidence`
- explanations

## Step 7: Alerts and Dashboard

When analytics detects a significant anomaly or risk level change, it publishes:

- `anomaly.detected.v1`
- `risk-score.changed.v1`
- `recommendation.created.v1` later

`alert-service` consumes those events, deduplicates alerts by device, type,
severity, and time window, and exposes them to the frontend.

The frontend displays:

- latest device status
- charts
- baseline deviation
- anomalies
- risk score
- explanations
- recommendations
- alerts

## Failure Handling

The system assumes at-least-once delivery. Therefore:

- producers use event IDs and correlation IDs
- consumers are idempotent
- poison messages go to DLQ topics
- important future workflows can use the outbox pattern
- duplicate and out-of-order telemetry is tracked explicitly as data quality
