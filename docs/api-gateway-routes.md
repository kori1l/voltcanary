# API Gateway Routes

The API Gateway is the only external HTTP entry point for the browser and public clients.

## Public Routes

- `POST /auth/register` -> `identity-service`
- `POST /auth/login` -> `identity-service`
- `POST /auth/refresh` -> `identity-service`
- `POST /auth/logout` -> `identity-service`
- `POST /auth/forgot-password` -> `identity-service`
- `POST /auth/reset-password` -> `identity-service`
- `POST /auth/email-verification/request` -> `identity-service`
- `POST /auth/email-verification/confirm` -> `identity-service`
- `GET /actuator/health` -> `api-gateway`
- `GET /actuator/info` -> `api-gateway`

## Protected Routes

Protected routes require `Authorization: Bearer <accessToken>`. The gateway validates the access JWT and forwards:

- `X-User-Id`
- `X-User-Email`
- `X-User-Role`

Incoming client-provided `X-User-*` headers are stripped before these values are set.

Gateway authorization policy:

- `/auth/admin/**` requires `ADMIN`.
- Non-admin protected routes require a valid access token at the gateway.
- Resource ownership checks stay in the owning backend service.

Route ownership:

- `/auth/**` -> `identity-service`
- `/locations/**` -> `device-service`
- `/devices/**` -> `device-service`
- `/device-types/**` -> `device-service`
- `/ingestion/**` -> `ingestion-service`
- `/telemetry/**` -> `telemetry-service`
- `/analytics/**` -> `analytics-service`
- `/alerts/**` -> `alert-service`
- `/simulation/**` -> `simulator-service`
- `/ai-agent/**` -> `ai-agent-service`

## Gateway Policies

- Connect timeout: `GATEWAY_CONNECT_TIMEOUT_MS`, default `2000`.
- Response timeout: `GATEWAY_RESPONSE_TIMEOUT`, default `5s`.
- CORS allowed origins: `GATEWAY_CORS_ALLOWED_ORIGINS`, default `http://localhost:3000`.
- Request size limits:
  - default: `GATEWAY_REQUEST_MAX_BYTES`, default `1048576`.
  - auth: `GATEWAY_AUTH_REQUEST_MAX_BYTES`, default `65536`.
  - ingestion: `GATEWAY_INGESTION_REQUEST_MAX_BYTES`, default `262144`.
- Rate limits:
  - auth login/register: `GATEWAY_AUTH_RATE_LIMIT`, default `30` per window.
  - ingestion: `GATEWAY_INGESTION_RATE_LIMIT`, default `120` per window.
  - general: `GATEWAY_GENERAL_RATE_LIMIT`, default `600` per window.
  - window: `GATEWAY_RATE_LIMIT_WINDOW`, default `1m`.
  - The current limiter is in-memory per gateway instance. Use Redis-backed distributed rate limiting before running multiple gateway replicas.
- Downstream health checks are optional and disabled by default:
  - `GATEWAY_DOWNSTREAM_HEALTH_ENABLED=false`
  - `GATEWAY_DOWNSTREAM_HEALTH_TIMEOUT=2s`

Gateway-generated downstream failures return `503` with `code=downstream_unavailable`.

## JWT Verification

The gateway verifies access tokens with configured public keys:

- `JWT_KEY_ID` selects the active key id for local/dev defaults.
- `JWT_PUBLIC_KEYS` contains allowed verification keys by `kid`.
- `JWT_ISSUER` and `JWT_AUDIENCE` are enforced.

This keeps the gateway ready for asymmetric signing and key rotation.

## API Versioning

Current public routes remain unversioned to avoid breaking the existing frontend and clients. New externally consumed contracts should be introduced under `/api/v1/...` once the frontend and service contracts are versioned together. Internal service paths should not be exposed directly for versioning purposes.

## Error Shape

Gateway-generated errors follow the service error shape:

- `code`
- `message`
- `path`
- `correlationId`
- `timestamp`

The gateway does not include downstream target URIs or service internals in public error responses.

## Metrics

Gateway metrics are exposed through actuator and Prometheus:

- `voltcanary.gateway.requests` tracks request latency by `routeId`, HTTP method, and status.
- `voltcanary.gateway.downstream.failures` tracks gateway-generated downstream failures.
- Spring Cloud Gateway and JVM metrics remain available through `/actuator/metrics` and `/actuator/prometheus`.

## Service Boundaries

External browser and public client traffic must enter through the API Gateway. Internal synchronous service calls should use internal service DNS names on the private Docker/Kubernetes network. Asynchronous integration should use Redpanda/Kafka topics rather than calling public gateway routes.

## Production Edge Assumptions

TLS is expected to terminate at ingress or the external load balancer in production. The gateway is configured with `server.forward-headers-strategy=framework`, so ingress must provide standard forwarded headers. Only trusted proxy or ingress hops should be allowed to set `X-Forwarded-*`; public clients must not be able to bypass the trusted edge.
