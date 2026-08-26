# Service Boundaries

This document defines service responsibilities, data ownership, and interaction
patterns. The core rule is: a service owns its data and other services interact
through APIs or events, not by reading another service database directly.

## Service Ownership

| Service | Owns | Does Not Own |
| --- | --- | --- |
| `api-gateway` | Public routing boundary, CORS, correlation ID, future auth enforcement | Business data |
| `identity-service` | Users, roles, refresh tokens, login audit | Devices, telemetry, analytics results |
| `device-service` | Accounts, locations, devices, device types, sensors, data sources, analytics config, maintenance windows | Raw telemetry, risk score history, alerts |
| `ingestion-service` | Ingestion validation state, source runtime-config read model, idempotency/rate-limit correctness state, active connector leases | Canonical telemetry storage, device source ownership |
| `telemetry-service` | Telemetry readings, quality flags, aggregates, latest readings | Device metadata ownership, analytics conclusions |
| `analytics-service` | Baselines, features, anomalies, risk scores, predictions, recommendations, analytics jobs, maintenance read model | Raw ingestion API, alert lifecycle, maintenance ownership |
| `alert-service` | Alerts, alert events, notification channels, deduplication state, maintenance read model, suppression audit | Risk score calculation, maintenance ownership |
| `ml-service` | Model metadata, model runs, datasets, inference contracts, model artifacts metadata | Device inventory and alert lifecycle |
| `simulator-service` | Simulation YAML configs, simulation versions, simulation runs, simulator telemetry production state | Canonical telemetry storage, source ownership |
| `ai-agent-service` | Agent provider configuration, model configuration, plan/execution orchestration state | Device, telemetry, analytics, alert, or simulator domain ownership |
| `frontend` | Client-side views and UI state | Server-side domain data |

## Responsibilities

### API Gateway

The gateway is the single entry point for frontend and external clients. It
routes traffic to internal services and applies cross-cutting HTTP concerns:
CORS, correlation ID, request logging, and later JWT validation and rate
limiting.

Routes:

- `/auth/**` -> `identity-service`
- `/accounts/**`, `/locations/**`, `/devices/**`, `/device-types/**` -> `device-service`
- `/ingestion/**` -> `ingestion-service`
- `/telemetry/**` -> `telemetry-service`
- `/analytics/**` -> `analytics-service`
- `/alerts/**` -> `alert-service`
- `/simulation/**` -> `simulator-service`
- `/ai-agent/**` -> `ai-agent-service`
- `/ml/**` -> `ml-service` later for admin/analyst flows

### Identity Service

Owns authentication and identity:

- user registration
- login
- refresh token rotation
- logout
- current user
- roles
- login audit later

REST API:

- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `GET /auth/me`

### Device Service

Owns the device management domain:

- accounts and tenant-like boundaries
- locations
- devices
- device types
- sensors
- data sources
- analytics configuration
- maintenance windows and their policy flags

REST API:

- `GET /accounts/current`
- `POST /locations`
- `GET /locations`
- `GET /locations/{id}`
- `PATCH /locations/{id}`
- `DELETE /locations/{id}`
- `POST /devices`
- `GET /devices`
- `GET /devices/{id}`
- `PATCH /devices/{id}`
- `DELETE /devices/{id}`
- `GET /device-types`
- `POST /devices/{deviceId}/data-sources`
- `GET /devices/{deviceId}/data-sources`
- `GET /devices/{deviceId}/analytics-config`
- `PATCH /devices/{deviceId}/analytics-config`
- `GET/POST /devices/{deviceId}/maintenance-windows`

### Ingestion Service

Accepts HTTP push and MQTT telemetry through registry-backed inbound adapters,
and runs HTTP polling, Modbus TCP, and OPC UA active connectors from the local
source runtime-config read model. It validates input and publishes raw
telemetry events.

REST API:

- `POST /ingestion/readings`
- `POST /ingestion/readings/batch`

Publishes:

- `telemetry.raw.v1` with `TelemetryReceivedEvent`

Kafka key:

- `deviceId`, so readings from one device are processed in partition order.

### Telemetry Service

Consumes raw telemetry, normalizes readings, tracks data quality, stores
time-series data, updates latest reading cache later, and publishes validated
telemetry.

Consumes:

- `telemetry.raw.v1`

Publishes:

- `telemetry.validated.v1`
- `device.status-changed.v1` later

REST API:

- `GET /telemetry/devices/{deviceId}/readings`
- `GET /telemetry/devices/{deviceId}/latest`
- `GET /telemetry/devices/{deviceId}/quality`
- `GET /telemetry/devices/{deviceId}/aggregates` later

### Analytics Service

Calculates baseline, features, trends, anomalies, risk score, explanations,
recommendations, and calls the ML service for inference.

Consumes:

- `telemetry.validated.v1`
- `maintenance-window.changed.v1`

Publishes:

- `baseline.updated.v1`
- `features.calculated.v1`
- `anomaly.detected.v1`
- `risk-score.changed.v1`
- `recommendation.created.v1`
- `model-inference.completed.v1`

REST API:

- `GET /analytics/devices/{deviceId}/baseline`
- `GET /analytics/devices/{deviceId}/features`
- `GET /analytics/devices/{deviceId}/anomalies`
- `GET /analytics/devices/{deviceId}/risk`
- `GET /analytics/devices/{deviceId}/recommendations`
- `POST /analytics/devices/{deviceId}/recalculate`

Maintenance windows are projected locally. Analytics excludes matching samples
from derived calculations according to the window policy and reconciles missed
window changes through device-service internal APIs.

### Alert Service

Owns alert lifecycle. It creates alerts from analytics events, deduplicates
similar alerts, and exposes acknowledgement/resolution commands.

Consumes:

- `anomaly.detected.v1`
- `risk-score.changed.v1`
- `maintenance-window.changed.v1`
- `recommendation.created.v1` later

Publishes:

- `alert.created.v1`
- `alert.acknowledged.v1`
- `alert.resolved.v1`
- `notification.requested.v1` later

REST API:

- `GET /alerts`
- `GET /alerts/{id}`
- `POST /alerts/{id}/ack`
- `POST /alerts/{id}/resolve`

Maintenance windows are projected locally. Alert-service suppresses matching
new analytics events and auto-resolves overlapping unresolved alerts without
synchronous device-service lookups in the alert creation path.

### ML Service

Owns data science workflows:

- data preparation
- feature engineering
- model selection
- training
- evaluation
- model comparison
- model registry
- inference
- feedback loop later

REST API:

- `GET /health`
- `GET /ml/models`
- `POST /ml/inference`
- `POST /ml/train`
- `GET /ml/runs/{id}`

### Simulator Service

Owns simulation configuration and run history. It produces telemetry through
normal ingestion contracts rather than writing telemetry storage or publishing
canonical telemetry events directly.

REST API:

- `GET /simulation/devices/{deviceId}/config`
- `PUT /simulation/devices/{deviceId}/config`
- `POST /simulation/devices/{deviceId}/config/validate`
- `POST /simulation/devices/{deviceId}/start`
- `POST /simulation/devices/{deviceId}/stop`
- `GET /simulation/devices/{deviceId}/status`
- `GET /simulation/devices/{deviceId}/runs`
- `GET /simulation/runs/{runId}`

### AI Agent Service

Owns agent-facing planning, tool execution coordination, and model provider
configuration. It may call domain services through their public or internal
contracts, but it does not own their data.

REST API:

- `GET /ai-agent/tools`
- `GET /ai-agent/provider`
- `POST /ai-agent/plans`
- `POST /ai-agent/plans/{planId}/execute`
- provider and model configuration endpoints under `/ai-agent/settings`

## Interaction Types

REST is used for synchronous commands and queries:

- frontend -> api-gateway
- api-gateway -> Java services
- analytics-service -> ml-service
- simulator-service -> ingestion-service
- ai-agent-service -> domain service APIs through explicit tool boundaries
- ingestion-service -> device-service internal APIs for reconciliation/smoke diagnostics, not hot-path source validation

Events are used for asynchronous processing:

- ingestion-service -> telemetry-service
- device-service -> ingestion-service through compacted `source.runtime-config.v1`
- telemetry-service -> analytics-service
- analytics-service -> alert-service
- alert-service -> notification provider later

## Boundary Rules

1. No service reads another service database directly.
2. Events are versioned and must be backward compatible within a major version.
3. At-least-once processing is assumed; consumers must be idempotent.
4. Every event carries `eventId`, `correlationId`, `producer`, and `occurredAt`.
5. User-facing analytics must be explainable and must include confidence where applicable.
6. Device maintenance policy is owned by device-service and propagated by
   events plus reconciliation; consumers use local read models in hot paths.
