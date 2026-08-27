# Service Boundaries

This document defines service responsibilities, data ownership, and interaction
patterns. The core rule is: a service owns its data and other services interact
through APIs or events, not by reading another service database directly.

## Service Ownership

| Service | Owns | Does Not Own |
| --- | --- | --- |
| `api-gateway` | Public routing boundary, CORS, correlation ID, JWT validation, role checks, request size and rate limits | Business data |
| `identity-service` | Users, roles, refresh tokens, login audit | Devices, telemetry, analytics results |
| `device-service` | Accounts, locations, devices, device types, sensors, data sources, analytics config, maintenance windows | Raw telemetry, risk score history, alerts |
| `ingestion-service` | Ingestion validation state, source runtime-config read model, idempotency/rate-limit correctness state, active connector leases | Canonical telemetry storage, device source ownership |
| `telemetry-service` | Telemetry readings, quality flags, aggregates, latest readings | Device metadata ownership, analytics conclusions |
| `analytics-service` | Baselines, features, anomalies, risk scores, predictions, recommendations, analytics jobs, maintenance read model | Raw ingestion API, alert lifecycle, maintenance ownership |
| `alert-service` | Alerts, alert events, notification channels, deduplication state, maintenance read model, suppression audit | Risk score calculation, maintenance ownership |
| `ml-service` | Private ML runtime: training/inference execution, model bundle creation/loading, feature artifact access, artifact health | Analytics-owned lifecycle metadata, device inventory, alert lifecycle |
| `simulator-service` | Simulation YAML configs, simulation versions, simulation runs, simulator telemetry production state | Canonical telemetry storage, source ownership |
| `ai-agent-service` | Agent provider configuration, model configuration, plan/execution orchestration state | Device, telemetry, analytics, alert, or simulator domain ownership |
| `frontend` | Client-side views and UI state | Server-side domain data |

## Responsibilities

### API Gateway

The gateway is the single entry point for frontend and external clients. It
routes traffic to internal services and applies cross-cutting HTTP concerns:
CORS, correlation ID, request logging, JWT validation, role checks, request size
limits, and rate limiting.

Routes:

- `/auth/**` -> `identity-service`
- `/accounts/**`, `/locations/**`, `/devices/**`, `/device-types/**` -> `device-service`
- `/maintenance-operations/**` -> `device-service`
- `/ingestion/**` -> `ingestion-service`
- `/telemetry/**` -> `telemetry-service`
- `/analytics/**` -> `analytics-service`
- `/alerts/**` -> `alert-service`
- `/alert-rules/**` -> `alert-service`
- `/simulation/**` -> `simulator-service`
- `/ai-agent/**` -> `ai-agent-service`

### Identity Service

Owns authentication and identity:

- user registration
- login
- refresh token rotation
- logout
- current user
- roles
- login audit
- password reset and email verification
- admin user status, role, session, and deletion workflows

REST API:

- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `POST /auth/logout-all`
- `POST /auth/change-password`
- `POST /auth/forgot-password`
- `POST /auth/reset-password`
- `POST /auth/email-verification/request`
- `POST /auth/email-verification/confirm`
- `GET /auth/me`
- `GET /auth/admin/users`
- `PATCH /auth/admin/users/{userId}/status`
- `PATCH /auth/admin/users/{userId}/role`
- `DELETE /auth/admin/users/{userId}`
- `GET /auth/admin/users/{userId}/sessions`
- `POST /auth/admin/users/{userId}/sessions/revoke`

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
- `GET/POST/PATCH /maintenance-operations`
- `GET/POST/PATCH /maintenance-operations/{operationId}/outcomes`

### Ingestion Service

Accepts HTTP push, import, and MQTT telemetry through registry-backed inbound adapters,
and runs HTTP polling, Modbus TCP, and OPC UA active connectors from the local
source runtime-config read model. It validates input and publishes raw
telemetry events.

REST API:

- `POST /ingestion/readings`
- `POST /ingestion/readings/batch`
- `POST /ingestion/imports/readings`
- `POST /ingestion/imports/readings/batch`

Publishes:

- `telemetry.raw.v1` with `TelemetryReceivedEvent`

Kafka key:

- `deviceId`, so readings from one device are processed in partition order.

### Telemetry Service

Consumes raw telemetry, normalizes readings, tracks data quality, stores
time-series data, serves latest/metric/export queries, and publishes validated
telemetry.

Consumes:

- `telemetry.raw.v1`

Publishes:

- `telemetry.validated.v1`

REST API:

- `GET /telemetry/devices/{deviceId}/readings`
- `GET /telemetry/devices/{deviceId}/readings/export`
- `GET /telemetry/devices/{deviceId}/latest`
- `GET /telemetry/devices/{deviceId}/quality`
- `GET /telemetry/devices/{deviceId}/metrics`

### Analytics Service

Calculates baseline, features, trends, anomalies, risk score, explanations,
recommendations, and predictions. It owns the API-facing ML lifecycle:
dataset versions, labels, feature snapshots, training run metadata, model
registry records, deployment policies, and calls the private ML service for
fitting/inference execution.

Consumes:

- `telemetry.validated.v1`
- `maintenance-window.changed.v1`

Publishes:

- `anomaly.detected.v1`
- `risk-score.changed.v1`

REST API:

- `GET /analytics/devices/{deviceId}/baseline`
- `GET /analytics/devices/{deviceId}/features`
- `GET /analytics/devices/{deviceId}/anomalies`
- `GET /analytics/devices/{deviceId}/risk`
- `GET /analytics/devices/{deviceId}/recommendations`
- `POST /analytics/devices/{deviceId}/recalculate`
- `/analytics/ml/**` dataset, label, feature snapshot, training run, model
  registry, and deployment policy endpoints

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

REST API:

- `GET /alerts`
- `GET /alerts/summary`
- `GET /alerts/page`
- `GET /alerts/{id}`
- `POST /alerts/{id}/ack`
- `POST /alerts/{id}/resolve`
- `/alert-rules/**`
- `/alerts/notification-channels/**`
- `/alerts/notification-preferences`
- `GET /alerts/{id}/notification-attempts`

Maintenance windows are projected locally. Alert-service suppresses matching
new analytics events and auto-resolves overlapping unresolved alerts without
synchronous device-service lookups in the alert creation path.

Alert lifecycle changes are stored in `alert_events`; notification delivery is
driven by durable `notification_attempts`. Kafka lifecycle topics for alert
creation, acknowledgement, and resolution are a target extension, not current
runtime behavior; see
[../../target/alert-lifecycle-events.md](../../target/alert-lifecycle-events.md).

### ML Service

Owns private data science runtime workflows:

- fitting/training execution requested by analytics-service
- inference execution
- model bundle creation, verification, and loading
- artifact storage abstraction and artifact health
- in-process serving artifact cache
- deterministic runtime fallback behavior

REST API:

- `GET /health`
- `GET /health/artifacts`
- `GET /models`
- `POST /inference`
- `POST /train`
- `GET /runs/{id}`
- `GET /ml/models`
- `POST /ml/inference`
- `POST /ml/train`
- `GET /ml/runs/{id}`

The current FastAPI app mounts inference, model, and training routers both at
the service root and under `/ml` for compatibility. Analytics-service should
prefer the `/ml/...` paths.

### Simulator Service

Owns simulation configuration and run history. It produces telemetry through
normal ingestion contracts rather than writing telemetry storage or publishing
canonical telemetry events directly.

REST API:

- `GET /simulation/devices/{deviceId}/config`
- `PUT /simulation/devices/{deviceId}/config`
- `POST /simulation/devices/{deviceId}/config/validate`
- `POST /simulation/devices/{deviceId}/config/reset-template`
- `POST /simulation/devices/{deviceId}/start`
- `POST /simulation/devices/{deviceId}/stop`
- `GET /simulation/devices/{deviceId}/status`
- `GET /simulation/devices/{deviceId}/runs`
- `GET /simulation/runs/{runId}`
- `GET /simulation/devices/{deviceId}/config/versions`
- `GET /simulation/templates`
- `GET /simulation/presets`
- `GET /simulation/templates/{profile}`

### AI Agent Service

Owns agent-facing planning, tool execution coordination, and model provider
configuration. It may call domain services through their public or internal
contracts, but it does not own their data.

REST API:

- `GET /ai-agent/tools`
- `GET /ai-agent/provider`
- `GET /ai-agent/model-provider-capabilities`
- `GET/POST /ai-agent/model-providers`
- `GET/PUT/DELETE /ai-agent/model-providers/{providerId}`
- `POST /ai-agent/model-providers/{providerId}/set-default`
- `POST /ai-agent/model-providers/{providerId}/test`
- `POST /ai-agent/model-providers/{providerId}/rotate-secret`
- `GET/POST /ai-agent/model-providers/{providerId}/models`
- `PUT/DELETE /ai-agent/model-providers/{providerId}/models/{modelConfigId}`
- `POST /ai-agent/model-providers/{providerId}/models/{modelConfigId}/set-default`
- `POST /ai-agent/plans`
- `POST /ai-agent/plans/{planId}/execute`

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
- alert-service -> notification providers through durable notification attempts

## Boundary Rules

1. No service reads another service database directly.
2. Events are versioned and must be backward compatible within a major version.
3. At-least-once processing is assumed; consumers must be idempotent.
4. Every event carries `eventId`, `correlationId`, `producer`, and `occurredAt`.
5. User-facing analytics must be explainable and must include confidence where applicable.
6. Device maintenance policy is owned by device-service and propagated by
   events plus reconciliation; consumers use local read models in hot paths.
