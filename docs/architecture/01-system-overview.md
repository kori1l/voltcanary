# System Overview

## Current System

VoltCanary is a pre-production predictive IoT analytics platform for connected
appliances and small physical assets. It accepts telemetry from HTTP, MQTT,
simulators, imports, and active connector sources; stores time-series readings;
calculates operational signals; and exposes device, analytics, alert,
maintenance, ML, and administration workflows through a React console.

The system reports observed behavior changes, anomaly signals, risk scores,
confidence, explanations, and recommended checks. It must not present limited
smart-plug telemetry as exact failure diagnosis.

## Core Topology

```text
Collectors / MQTT / Active Connectors / Simulator / Imports
        |
        v
API Gateway or Ingestion Transport
        |
        v
ingestion-service
        |
        v
telemetry.raw.v1
        |
        v
telemetry-service -> TimescaleDB -> telemetry.validated.v1
        |
        v
analytics-service <-> ml-service
        |
        v
alert-service
        |
        v
frontend
```

Business domains own their data in service-local databases. Services interact
through public gateway routes, internal REST calls, and Kafka-compatible events;
no service reads another service database directly.

## Services

| Service | Responsibility | Port |
| --- | --- | ---: |
| `api-gateway` | External HTTP boundary, routing, CORS, JWT validation, correlation IDs, request limits | 8080 |
| `identity-service` | Users, roles, login, refresh tokens, account lifecycle events | 8081 |
| `device-service` | Accounts, locations, devices, sensors, data sources, source tokens, maintenance windows | 8082 |
| `ingestion-service` | HTTP/MQTT/import ingress, source-token checks, canonical telemetry publication, active connectors | 8083 |
| `telemetry-service` | Raw event consumption, telemetry validation, quality flags, TimescaleDB persistence, validated events | 8084 |
| `analytics-service` | Baselines, features, anomalies, risk, recommendations, analytics jobs, ML calls | 8085 |
| `alert-service` | Alerts, alert rules, deduplication, lifecycle, notification channels and attempts | 8086 |
| `simulator-service` | YAML simulation configs, run history, demo telemetry production through ingestion | 8087 |
| `ai-agent-service` | Agent planning/execution surface and model provider configuration | 8088 |
| `ml-service` | Datasets, feature snapshots, training runs, model registry, deployment policy, inference | 8090 |
| `frontend` | React operator console | 3000 |

## Current Telemetry Boundary

`ingestion-service` is the unified telemetry data-plane entry point. HTTP push,
MQTT messages, historical imports, HTTP polling, Modbus TCP, and OPC UA are
modeled as adapters that emit canonical `IncomingTelemetryMessage` values
before the common pipeline performs source validation, idempotency, rate
limiting, and Kafka publication.

`device-service` is the control-plane owner for data source configuration. It
publishes compacted `source.runtime-config.v1` events. Each ingestion replica
builds a local read model from that topic, so normal telemetry validation does
not synchronously call `device-service`.

## Main Events

| Topic | Producer | Consumers | Key |
| --- | --- | --- | --- |
| `source.runtime-config.v1` | `device-service` | `ingestion-service` replicas | `dataSourceId` |
| `telemetry.raw.v1` | `ingestion-service` | `telemetry-service` | `deviceId` |
| `telemetry.validated.v1` | `telemetry-service` | `analytics-service` | `deviceId` |
| `maintenance-window.changed.v1` | `device-service` | `analytics-service`, `alert-service` | `deviceId` |
| `anomaly.detected.v1` | `analytics-service` | `alert-service` | `deviceId` |
| `risk-score.changed.v1` | `analytics-service` | `alert-service` | `deviceId` |

Detailed event policy lives in `docs/events/event-envelope.md` and
`docs/architecture/04-event-driven-architecture.md`.

## Runtime State

- PostgreSQL stores service-owned business data for identity, device,
  analytics, alert, simulator, and AI-agent domains.
- TimescaleDB stores telemetry readings, quality state, and time-series query
  data.
- Redpanda/Kafka carries asynchronous events and compacted read-model streams.
- Redis is used for distributed idempotency, rate limiting, connector leases,
  and short-lived runtime state.
- MinIO/S3-compatible storage is used for ML artifact storage in production-like
  ML profiles.

## Deployment Status

Local development supports Docker Compose and a Docker Desktop Kubernetes
overlay. The Kubernetes overlay runs stateless application services with
replicas, HPA, and PodDisruptionBudgets, but it still uses local development
mounts and runtime language images. Real production deployment still requires
immutable service images, external secret management, ingress/TLS, stateful HA,
backup/restore, network policies, service monitors, alert rules, and runbooks.

## Architecture References

- Service ownership: `docs/architecture/02-service-boundaries.md`
- Telemetry flow: `docs/architecture/03-data-flow.md`
- Event policy: `docs/architecture/04-event-driven-architecture.md`
- Storage: `docs/architecture/05-storage-design.md`
- Deployment: `docs/architecture/06-kubernetes-deployment.md`
- Observability: `docs/architecture/07-observability.md`
- Security: `docs/architecture/08-security.md`
- Horizontal scaling: `docs/architecture/09-horizontal-scaling-matrix.md`
- Consistency: `docs/architecture/10-consistency-contracts.md`
- Target architecture: `docs/architecture/12-target-architecture.md`
