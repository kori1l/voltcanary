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

## Toolchain Baselines

- Java 21
- Node.js 22.12.0 or newer within the Node 22 line
- npm 10 or newer
- Python 3.12

Local metadata and CI use these baselines. Older Node versions, including Node
14, are not supported.

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
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
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
npm ci
npm run dev
```

Frontend URL:

```text
http://localhost:3000
```

## Local Infrastructure

Start the local infrastructure from Docker Compose:

```bash
docker compose --profile infra up -d
```

Default PostgreSQL connection:

```text
Host: localhost
Port: 5432
Database: voltcanary
User: voltcanary
Password: change_me
```

Default TimescaleDB connection:

```text
Host: localhost
Port: 5433
Database: voltcanary_telemetry
User: voltcanary
Password: change_me
```

Redpanda is exposed on `localhost:9092`, Redis on `localhost:6379`, MinIO on
`http://localhost:9000`, and MinIO Console on `http://localhost:9001`.

Start observability:

```bash
docker compose --profile observability up -d
```

Prometheus is available at `http://localhost:9090`, Grafana at
`http://localhost:3001` with `admin` / `admin` by default.
Loki is available at `http://localhost:3100` and is provisioned as a Grafana
datasource for local container logs.

View logs in Grafana:

1. Open `http://localhost:3001`.
2. Go to Explore.
3. Select the `Loki` datasource.
4. Query all local Compose logs:

```logql
{compose_project="voltcanary"}
```

Query one service:

```logql
{compose_service="api-gateway"}
```

Start application containers together with infrastructure:

```bash
docker compose --profile app up
```

Environment variables are documented in `.env.example`.

## Local Kubernetes

The current local Kubernetes deployment is documented in
`docs/k8s-local.md`. It uses Docker Desktop Kubernetes, Kustomize, local
`hostPath` source mounts, and production-like controls for stateless services.

Start the stack:

```powershell
kubectl apply -k deploy/k8s/overlays/local
kubectl get deploy,hpa,pdb,pods -n voltcanary
```

Expose the frontend and API Gateway locally:

```powershell
kubectl -n voltcanary port-forward svc/frontend 3000:3000
kubectl -n voltcanary port-forward svc/api-gateway 8080:8080
```

The stateless services run with two replicas, HPA from two to four pods, and
PodDisruptionBudgets. Stateful services remain single-replica locally and need
clustered configuration or managed services for real production HA.

Kubernetes Dashboard:

```text
https://localhost:8443
```

Grafana:

```text
http://localhost:3001
```

## Documentation

Key documents:

- `docs/architecture/01-system-overview.md`
- `docs/architecture/02-service-boundaries.md`
- `docs/architecture/03-data-flow.md`
- `docs/architecture/06-kubernetes-deployment.md`
- `docs/architecture/07-observability.md`
- `docs/architecture/09-horizontal-scaling-matrix.md`
- `docs/k8s-local.md`
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
