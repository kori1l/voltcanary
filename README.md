# VoltCanary

VoltCanary is a predictive IoT analytics platform for connected appliances and
small physical assets.

It collects telemetry from smart plugs and sensors, builds a baseline of normal
behavior, detects anomalies, calculates degradation risk, and provides users
with charts, alerts, forecasts, and maintenance recommendations.

## Core Idea

```text
Smart Plug / Sensor / CSV / MQTT
        ->
Ingestion Service
        ->
Kafka-compatible broker
        ->
Telemetry Service
        ->
Time-series storage
        ->
Analytics Service
        ->
ML Service
        ->
Risk Score / Forecast / Recommendation
        ->
User Dashboard
```

VoltCanary does not try to promise exact failure diagnosis from limited data.
It reports behavior changes, anomalies, confidence, and recommended checks.

## Tech Stack

- Java 21
- Spring Boot 3
- Spring Cloud Gateway
- Spring Data JPA
- Spring Kafka
- PostgreSQL
- TimescaleDB
- Redis
- Redpanda / Kafka
- Python FastAPI
- React + TypeScript
- Vite
- Docker Compose
- Kubernetes
- Helm
- Prometheus + Grafana
- JUnit 5 + Testcontainers

## Project Status

Early development stage.

Current focus:

- repository and service skeletons
- event contracts
- basic service boundaries
- local development setup
- first vertical slice for telemetry ingestion

## Repository Structure

```text
voltcanary/
  docs/                 Architecture, ADRs, API docs, events, interview notes
  backend/              Java Spring Boot monorepo modules
  ml-service/           Python FastAPI ML service
  frontend/             React + TypeScript web cabinet
  deploy/               Docker Compose, Kubernetes, Helm
  scripts/              Local setup and demo scripts
  test-data/            Demo telemetry samples
```

## Services

| Service | Runtime | Port | Path |
| --- | --- | ---: | --- |
| API Gateway | Java / Spring Cloud Gateway | 8080 | `backend/services/api-gateway` |
| Identity Service | Java / Spring Boot | 8081 | `backend/services/identity-service` |
| Device Service | Java / Spring Boot | 8082 | `backend/services/device-service` |
| Ingestion Service | Java / Spring Boot | 8083 | `backend/services/ingestion-service` |
| Telemetry Service | Java / Spring Boot | 8084 | `backend/services/telemetry-service` |
| Analytics Service | Java / Spring Boot | 8085 | `backend/services/analytics-service` |
| Alert Service | Java / Spring Boot | 8086 | `backend/services/alert-service` |
| ML Service | Python / FastAPI | 8090 | `ml-service` |
| Frontend | React / Vite | 3000 | `frontend` |

## Backend

Java services are managed from the Maven parent at `backend/pom.xml`.

```bash
cd backend
mvn test
```

Every Java service exposes:

- `GET /actuator/health`
- `GET /internal/ping`

Run API Gateway:

```bash
cd backend
mvn -pl services/api-gateway spring-boot:run -Dspring-boot.run.profiles=local
```

Run a regular Java service:

```bash
cd backend
mvn -pl services/identity-service spring-boot:run
```

Services that depend on shared modules may need common modules installed first:

```bash
cd backend
mvn -pl common/common-events -am install
```

## ML Service

```bash
cd ml-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8090
```

Health endpoint:

```text
http://localhost:8090/health
```

Swagger UI:

```text
http://localhost:8090/docs
```

## Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend URL:

```text
http://localhost:3000
```

## Local Infrastructure

Start PostgreSQL/TimescaleDB and Redpanda from Docker Compose:

```bash
docker compose up -d postgres redpanda
```

Default PostgreSQL connection:

```text
Host: localhost
Port: 5432
Database: voltcanary
User: voltcanary
Password: change_me
```

Environment variables are documented in `.env.example`.

## Documentation

Key documents:

- `docs/architecture/01-system-overview.md`
- `docs/architecture/02-service-boundaries.md`
- `docs/architecture/03-data-flow.md`
- `docs/events/event-envelope.md`
- `docs/interview/talking-points.md`

## MVP Roadmap

1. Repository and service skeletons
2. Local infrastructure with Docker Compose
3. User registration and login
4. Device creation and data source binding
5. REST telemetry ingestion
6. Kafka event publishing and telemetry storage
7. Basic charts in frontend
8. Baseline and anomaly detection
9. Risk score and recommendations
10. Alerts and acknowledgement flow
11. ML inference and model registry expansion
12. Kubernetes deployment with observability
