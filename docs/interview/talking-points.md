# Interview Talking Points

This document gives concise answers for architecture and system design
discussions around VoltCanary.

## Why Microservices?

VoltCanary has several domains with different scaling and ownership needs:
identity, device management, telemetry ingestion, time-series processing,
analytics, alerts, ML, and frontend delivery. Microservices make those
boundaries explicit.

The most important reason is not fashion. The system has very different
workloads:

- ingestion is write-heavy and bursty
- telemetry storage is time-series heavy
- analytics can be asynchronous and CPU-heavy
- ML has a different runtime and dependency ecosystem
- alerts need reliable deduplication and lifecycle management

Microservices allow independent scaling, clear data ownership, and production
like deployment. The trade-off is more operational complexity, so the project
uses a monorepo and Docker Compose locally to keep development manageable.

## Why Event-Driven Architecture?

Telemetry processing is naturally asynchronous. A smart plug should not wait for
baseline calculation, ML inference, risk scoring, alert creation, and dashboard
updates in one synchronous request.

Events decouple ingestion from processing:

```text
Ingestion -> telemetry.raw.v1 -> Telemetry -> telemetry.validated.v1 -> Analytics
```

Benefits:

- ingestion remains fast
- downstream services can scale independently
- transient failures do not block data acceptance
- analytics can be replayed from events
- new consumers can be added without changing the producer

The trade-off is that the system becomes eventually consistent and consumers
must handle duplicates, retries, and out-of-order data.

## Why Kafka / Redpanda?

Kafka is a strong fit for durable, ordered, replayable telemetry streams.
Redpanda is Kafka-compatible and easier to run locally because it does not need
ZooKeeper.

Kafka/Redpanda is used because VoltCanary needs:

- durable event log
- partitioning by `deviceId`
- replay for analytics changes
- consumer groups for horizontal scaling
- at-least-once delivery
- decoupling between services

For the MVP, Redpanda is convenient locally. The architecture keeps Kafka
compatibility, so moving to managed Kafka later remains possible.

## Why TimescaleDB?

Telemetry is time-series data. It needs efficient queries by device, metric, and
time range, plus downsampling and aggregates for charts.

TimescaleDB is a good MVP choice because:

- it is PostgreSQL-compatible
- SQL remains familiar
- it supports hypertables and time-series optimizations
- it can store raw readings and aggregates
- it reduces operational complexity compared with introducing a separate query language

The trade-off is that at very large scale ClickHouse or another analytical store
could become more appropriate. For the current scope, TimescaleDB keeps the
system practical and production-like.

## How Do You Handle Duplicates?

The architecture assumes at-least-once delivery, so duplicates are expected.

Duplicate handling happens in multiple places:

- ingestion calculates an idempotency key such as `deviceId + sourceId + timestamp + metricName`
- events carry a unique `eventId`
- Kafka messages use `deviceId` as key to keep per-device ordering
- telemetry-service checks whether a reading already exists before writing
- analytics consumers store processed event IDs or derive idempotent results
- alert-service deduplicates by `deviceId + alertType + severity + timeWindow`

This prevents duplicate HTTP retries or duplicate Kafka delivery from creating
incorrect telemetry, duplicated risk changes, or alert spam.

## How Do You Handle Out-of-Order Telemetry?

Out-of-order telemetry is normal in IoT systems because devices can buffer data,
lose connectivity, reconnect, and send old readings later.

VoltCanary handles this by:

- using the reading timestamp from the device as domain time
- using Kafka partitioning by `deviceId` to reduce reordering within one device
- storing readings by timestamp in TimescaleDB
- marking late readings with quality flags such as `OUT_OF_ORDER` or `LATE_ARRIVAL`
- recalculating affected aggregates or scheduling correction jobs
- making analytics windows tolerant of late data

The system distinguishes processing time from event time. Charts and baselines
are based on event time, while operational monitoring can also track ingestion
latency.

## How Does Risk Score Work?

Risk score is an explainable score from 0 to 100. It should not claim exact
failure diagnosis. It combines multiple signals:

- anomaly score
- consumption trend
- cycle deviation
- data quality risk
- offline or missing data risk
- ML inference output later

Example formula:

```text
riskScore =
  anomalyScore * 0.35
+ trendScore * 0.25
+ cycleDeviationScore * 0.20
+ dataQualityRisk * 0.10
+ offlineRisk * 0.10
```

Risk levels:

- `0-30`: LOW
- `31-60`: MEDIUM
- `61-80`: HIGH
- `81-100`: CRITICAL

The important part is explainability. The UI should show reasons such as:

- average runtime increased by 22% over the last 14 days
- energy per cycle is above baseline
- power spikes were detected four times in the last 24 hours
- data quality is low because readings are missing

## How Would You Deploy It to Kubernetes?

Each service becomes a containerized deployment:

- Java services as Spring Boot containers
- ML service as a FastAPI container
- frontend as a static web container or Node/Vite build served by Nginx

Kubernetes resources:

- `Deployment` per service
- `Service` per service
- `ConfigMap` for non-secret config
- `Secret` for DB passwords, JWT secrets, provider tokens
- `readinessProbe` and `livenessProbe`
- resource requests and limits
- Ingress for frontend and API gateway
- Helm chart for repeatable deployment

Namespaces:

- `voltcanary-dev`
- `voltcanary-stage`
- `observability`

Ingress:

- `app.voltcanary.local` -> frontend
- `api.voltcanary.local` -> api-gateway
- `grafana.voltcanary.local` -> Grafana

Observability:

- Spring Actuator
- Micrometer Prometheus metrics
- Grafana dashboards
- structured logs with `correlationId`
- OpenTelemetry tracing later

For local development, Docker Compose is enough. Kubernetes is used as the
production-like target once Dockerfiles, Helm templates, health probes, secrets,
and observability are ready.

## What Was Simplified in the MVP?

The MVP intentionally starts with skeletons and vertical slices:

- REST ingestion before MQTT and vendor adapters
- H2/local startup support before full PostgreSQL wiring in every service
- mock ML inference before real model training
- rule-based risk scoring before advanced ML models
- basic alert lifecycle before notification providers
- Vite frontend skeleton before full charts and dashboards

These simplifications keep the project deliverable while preserving the target
architecture.
