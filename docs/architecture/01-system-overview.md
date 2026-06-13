# System Overview

## What VoltCanary Is

VoltCanary is a predictive IoT analytics platform for connected appliances and
small physical assets. It collects telemetry from smart plugs and sensors,
stores time-series data on the server side, builds baselines of normal device
behavior, detects anomalies, calculates degradation or failure risk, and shows
charts, forecasts, alerts, recommendations, and maintenance insights in a web
cabinet.

The first stage focuses on smart plug telemetry:

- active power
- voltage
- current
- energy
- relay state
- signal quality / RSSI
- online/offline status

Later stages add context from temperature, humidity, pressure, vibration,
door/open-state sensors, leak sensors, air quality, external weather, and
device-specific APIs.

## Problem It Solves

Most smart plug applications show current power and total consumption. That is
useful, but it does not answer operational questions:

- Is the device behavior changing?
- Is energy consumption growing under similar conditions?
- Are there anomalies or suspicious work cycles?
- Is maintenance recommended?
- How confident is the system?
- Which factors influenced the risk score?

VoltCanary turns raw measurements into explainable engineering conclusions. It
must not claim exact failure diagnosis without enough data. The system reports
signs, deviations, probabilities, confidence levels, and recommended checks.

## Main Data Flow

```text
Smart Plug / Sensor / CSV / API
        |
        v
Ingestion Service
        |
        v
Kafka-compatible Message Broker
        |
        v
Telemetry Service
        |
        v
TimescaleDB / Time-Series Storage
        |
        v
Analytics Service
        |
        v
ML Service
        |
        v
Risk Score / Forecast / Anomaly / Explanation
        |
        v
Alert Service + Web Dashboard
```

## Main Services

| Service | Responsibility | Port |
| --- | --- | ---: |
| `api-gateway` | Single backend entry point, routing, CORS, correlation IDs, future JWT checks | 8080 |
| `identity-service` | Users, roles, login, JWT, refresh tokens | 8081 |
| `device-service` | Accounts, locations, devices, sensors, data sources, analytics config | 8082 |
| `ingestion-service` | Accept telemetry, validate input, publish raw telemetry events | 8083 |
| `telemetry-service` | Normalize telemetry, track quality, store time-series data, publish validated events | 8084 |
| `analytics-service` | Baselines, features, anomalies, risk score, recommendations, ML calls | 8085 |
| `alert-service` | Alert creation, deduplication, acknowledgement, resolution | 8086 |
| `ml-service` | Data preparation, model selection, training, evaluation, inference, model registry | 8090 |
| `frontend` | Web cabinet for devices, charts, alerts, recommendations, and analytics | 3000 |

## Main Technologies

Backend:

- Java 21
- Spring Boot 3
- Spring Cloud Gateway
- Spring Web
- Spring Data JPA
- Spring Security
- Spring Kafka
- Flyway
- MapStruct
- Micrometer
- Actuator
- JUnit 5
- Testcontainers

ML service:

- Python
- FastAPI
- Pydantic
- Uvicorn
- Pandas / NumPy / Scikit-learn later
- MLflow later or optional

Frontend:

- React
- TypeScript
- Vite
- API client
- JWT handling later

Infrastructure:

- PostgreSQL for business data
- TimescaleDB for telemetry and aggregates
- Redpanda/Kafka for internal events
- Redis for cache, latest readings, idempotency keys, and rate limiting
- MinIO later for CSV uploads, model artifacts, reports, and exports
- Docker Compose for local development
- Kubernetes and Helm for production-like deployment
- Prometheus and Grafana for observability

Scaling readiness is tracked in
`docs/architecture/09-horizontal-scaling-matrix.md`.
