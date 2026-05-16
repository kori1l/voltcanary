# System Improvements Backlog

This document tracks service-level and system-level fixes that should be
addressed as VoltCanary moves from MVP scaffolding toward a production-ready
system.

## Identity Service

The current `identity-service` is a lightweight custom authentication service
for MVP use. It covers registration, login, access token issuance, refresh
tokens, and basic login audit. It is not yet a full Keycloak replacement.

### Critical

1. Add `/auth/refresh` to the API Gateway public paths.
   - Current issue: once an access token expires, the gateway requires a valid
     bearer token before allowing the refresh request.
   - File: `backend/services/api-gateway/src/main/java/com/voltcanary/gateway/filter/JwtAuthenticationGlobalFilter.java`

2. Implement real logout.
   - Current issue: `POST /auth/logout` returns a placeholder message.
   - Expected behavior: revoke the provided refresh token by setting
     `refresh_tokens.revoked_at`.

3. Add brute-force protection for login.
   - Add rate limits by email and IP.
   - Add temporary lockout after repeated failed attempts.
   - Keep the public error message generic.

4. Add user status.
   - Add a `status` field to `users`, for example `ACTIVE`, `DISABLED`,
     `LOCKED`, `PENDING_VERIFICATION`.
   - Login and refresh must reject disabled or locked users.

5. Check user status during refresh.
   - Current issue: refresh token validity is checked, but user status is not
     part of the decision.

6. Add tests for the auth flow.
   - Registration success.
   - Duplicate email.
   - Weak password rejected.
   - Login success.
   - Wrong password audit.
   - Refresh success.
   - Revoked refresh token rejected.
   - Expired refresh token rejected.
   - `/auth/me` with valid, invalid, and expired access token.
   - Logout revokes refresh token.

7. Ensure the gateway does not trust incoming `X-User-*` headers.
   - The gateway should overwrite or remove incoming `X-User-Id`,
     `X-User-Email`, and `X-User-Role` headers before forwarding requests.

### High

8. Add `logout-all` support.
   - Add an endpoint such as `POST /auth/logout-all`.
   - Revoke all active refresh tokens for the current user.

9. Add repository support for active user refresh tokens.
   - Add methods such as `findByUserIdAndRevokedAtIsNull`.
   - Needed for logout-all and session management.

10. Add cleanup for expired refresh tokens.
    - Use a scheduled job or maintenance command to remove or archive expired
      refresh tokens.

11. Strengthen access token claims.
    - Add `iss`.
    - Add `aud`.
    - Add `jti`.
    - Add an explicit token type claim, for example `typ=access`.

12. Improve refresh token rotation safety.
    - Detect reuse of an already revoked refresh token.
    - On reuse, revoke all refresh tokens for that user.

13. Improve login audit.
    - Store IP address.
    - Store user agent.
    - Store correlation/request id.
    - Store normalized failure category.

14. Add change password endpoint.
    - Add `POST /auth/change-password`.
    - Require current password.
    - Revoke all refresh tokens after a successful password change.

15. Add integration tests for gateway plus identity.
    - `/auth/login` is public.
    - `/auth/refresh` is public.
    - Protected endpoint without token returns `401`.
    - Protected endpoint with valid token succeeds.
    - Protected endpoint with expired token returns `401`.

16. Synchronize JWT validation code.
    - JWT parsing and validation logic currently exists in multiple services.
    - Prefer a shared internal library or a documented contract with consistent
      validation behavior.

### Medium

17. Consider asymmetric JWT signing.
    - Move from shared-secret `HS256` to `RS256` or `ES256`.
    - Identity signs with a private key.
    - Gateway and services verify with a public key.

18. Prepare for key rotation.
    - Add `kid` to JWT headers.
    - Support active and previous verification keys.

19. Add password reset flow.
    - Add forgot-password request endpoint.
    - Add one-time reset token with expiry.
    - Add reset confirmation endpoint.
    - Integrate email delivery later.

20. Add email verification.
    - Add `email_verified`.
    - Add verification token and confirmation endpoint.
    - Prevent or limit access for unverified users if required.

21. Expand roles and permissions.
    - Current model has a single string role, defaulting to `USER`.
    - Define roles such as `USER`, `ADMIN`, and `SUPPORT`.
    - Decide whether authorization is enforced in gateway, services, or both.

22. Add admin endpoints.
    - List users.
    - Disable or lock a user.
    - Change user role.
    - View active refresh sessions.
    - Revoke user sessions.

23. Improve API error consistency.
    - Keep login failure responses generic.
    - Ensure internal details do not leak through public error responses.
    - Keep detailed failure reasons only in audit logs.

24. Document the auth contract.
    - Endpoints.
    - Access and refresh token TTL.
    - JWT claims.
    - Roles.
    - Gateway-added downstream headers.
    - Expected downstream authorization behavior.

## API Gateway

The current `api-gateway` is the external HTTP entry point for the frontend and
external clients. It routes requests to backend services, validates access JWTs,
adds user context headers, handles CORS, and attaches a correlation id.

### Critical

1. Add `/auth/refresh` to public paths.
   - Current issue: refresh requests require a valid access token at the
     gateway, which breaks refresh once the access token expires.
   - File: `backend/services/api-gateway/src/main/java/com/voltcanary/gateway/filter/JwtAuthenticationGlobalFilter.java`

2. Strip or overwrite incoming `X-User-*` headers before forwarding.
   - Current issue: the gateway sets `X-User-Id`, `X-User-Email`, and
     `X-User-Role`, but it should explicitly prevent clients from spoofing
     those headers.
   - Expected behavior: remove incoming identity headers first, then set values
     only from validated JWT claims.

3. Validate required JWT claims.
   - Current issue: the gateway checks signature and expiration, but does not
     enforce required claim presence beyond using them.
   - Require non-empty `sub`.
   - Require expected `typ=access` once identity-service adds it.
   - Require expected `iss` and `aud` once those claims are introduced.

4. Add tests for gateway authentication behavior.
   - Public route without token succeeds for `/auth/login`.
   - Public route without token succeeds for `/auth/register`.
   - Public route without token succeeds for `/auth/refresh` after it is added.
   - Protected route without token returns `401`.
   - Protected route with malformed token returns `401`.
   - Protected route with expired token returns `401`.
   - Protected route with valid token forwards user headers.
   - Spoofed incoming `X-User-*` headers are overwritten.

### High

5. Centralize route definitions and remove duplication.
   - Current issue: route definitions differ between `application.yml` and
     `application-local.yml`.
   - Keep common route structure in the base config and override only target
     URIs or profile-specific values.

6. Add route-level timeout policy.
   - Define connect and response timeouts.
   - Avoid requests hanging indefinitely when a downstream service is slow.
   - Consider stricter timeouts for frontend-facing reads and longer timeouts
     for ingestion or analytics operations.

7. Add circuit breaker or failure handling for downstream services.
   - Use Spring Cloud Gateway circuit breaker support or a chosen resilience
     library.
   - Return consistent `503` responses when a service is unavailable.
   - Avoid cascading failures when a downstream service is overloaded.

8. Add rate limiting.
   - Apply stricter limits on `/auth/login`, `/auth/register`, and ingestion
     endpoints.
   - Add general per-IP or per-user limits for protected routes.
   - Decide whether Redis should back distributed rate limits.

9. Define request size limits.
   - Protect ingestion and auth endpoints from oversized bodies.
   - Return consistent `413 Payload Too Large`.

10. Improve CORS configuration.
    - Current issue: CORS is configured both in YAML and in `CorsConfig`.
    - Keep a single source of truth.
    - Move allowed origins to environment/config properties.
    - Support dev and production frontend origins explicitly.

11. Add structured access logs.
    - Keep correlation id, method, path, status, and duration.
    - Add route id and downstream target service when available.
    - Avoid logging tokens or sensitive request bodies.

12. Add security audit logs.
    - Log rejected authentication attempts at gateway level.
    - Include reason category such as missing token, malformed token, expired
      token, invalid signature.
    - Include correlation id.

13. Add OpenAPI or route documentation.
    - Document which external paths are exposed through the gateway.
    - Document which service owns each path.
    - Document which paths are public and which require authentication.

14. Add health checks for downstream dependencies.
    - Gateway health currently only reports its own process health.
    - Add optional readiness checks or synthetic pings for key downstream
      services if useful for operations.

### Medium

15. Prepare for asymmetric JWT verification.
    - Move away from shared `HS256` secret in gateway.
    - Verify tokens with public keys once identity-service signs with
      `RS256` or `ES256`.
    - Support `kid`-based key selection.

16. Add API versioning strategy.
    - Decide whether public routes should use `/api/v1/...`.
    - Avoid breaking frontend and external clients when service contracts
      evolve.

17. Add role or permission enforcement where appropriate.
    - Current gateway checks authentication but not authorization.
    - Decide which route families require `ADMIN`, `SUPPORT`, or regular
      `USER`.
    - Keep resource ownership checks in backend services.

18. Add service-to-service boundary documentation.
    - Clarify that external traffic goes through the gateway.
    - Clarify that internal synchronous traffic should use internal service DNS.
    - Clarify that asynchronous traffic should use Redpanda/Kafka.

19. Add consistent error response shape.
    - Gateway-generated errors should follow the same shape as service errors.
    - Include correlation id in error responses.
    - Avoid leaking internal target service details.

20. Add metrics by route.
    - Track request count, latency, status codes, and downstream failures by
      route id.
    - Expose metrics through actuator/prometheus.

21. Add production origin and TLS assumptions.
    - Document whether TLS terminates at ingress, gateway, or load balancer.
    - Ensure forwarded headers are handled correctly behind ingress.
    - Configure trusted proxy behavior explicitly.

## Telemetry Service

The current `telemetry-service` consumes raw telemetry events from Redpanda,
normalizes numeric metrics, stores time-series measurements in TimescaleDB, and
publishes validated telemetry events for analytics.

### Critical

1. Add ownership and authorization checks for telemetry reads.
   - Current issue: `/telemetry/devices/{deviceId}/...` accepts a device id but
     does not verify that the authenticated user can access that device.
   - Options:
     - call `device-service` for ownership checks;
     - use a replicated authorization model;
     - enforce ownership in gateway plus service.

2. Strengthen event validation before persistence.
   - Validate required fields: `deviceId`, `sourceId`, `timestamp`, `metrics`.
   - Reject or flag timestamps too far in the future.
   - Reject or flag timestamps older than the allowed ingestion window.
   - Reject unknown or unsupported metric names unless explicitly allowed.
   - Validate metric value ranges per metric.

3. Implement quality flag persistence and API.
   - Current issue: `/telemetry/devices/{deviceId}/quality` returns an empty
     list.
   - Persist quality flags for invalid, duplicate, normalized, out-of-order, or
     out-of-range readings.
   - Return quality history through the existing quality endpoint.

4. Add dead-letter handling for bad Kafka events.
   - Current issue: malformed or repeatedly failing events can poison consumer
     processing.
   - Add retry policy.
   - Add `telemetry.raw.dlq.v1`.
   - Include failure reason and original event metadata.

5. Make idempotency behavior explicit.
   - Current issue: duplicate metric rows are ignored at insert time, while
     duplicate events are only partially represented by quality flags.
   - Define whether duplicate events should publish validated events.
   - Track duplicate count and reason in quality data.

6. Add tests for the telemetry pipeline.
   - Raw event with numeric metrics creates measurement rows.
   - Non-numeric metrics are ignored or flagged according to policy.
   - `NaN` and infinite values are rejected or flagged.
   - Duplicate events do not duplicate rows.
   - Valid persisted event publishes `TelemetryValidatedEvent`.
   - Invalid event goes to DLQ or records quality flags.
   - `/latest` and `/readings` return expected data.

### High

7. Use batch inserts for measurement persistence.
   - Current issue: `TelemetryMeasurementRepository.insertAll` inserts rows one
     by one.
   - Use JDBC batch updates for better throughput.
   - Preserve duplicate handling.

8. Add batch query and aggregation support for charts.
   - Current `/readings` returns raw points with a hard `limit 1000`.
   - Add downsampling or bucketed aggregation for large time ranges.
   - Support interval parameters such as `1m`, `5m`, `1h`.

9. Add TimescaleDB hypertable setup.
   - Ensure `telemetry.measurements` is a hypertable.
   - Add appropriate chunk interval.
   - Verify indexes match query patterns.

10. Add TimescaleDB retention and compression policy.
    - Define how long raw measurements are kept.
    - Compress older chunks.
    - Keep aggregates longer than raw data if needed.

11. Add metric registry or metric metadata.
    - Define supported metrics such as `activePowerW`, `voltageV`, `currentA`,
      `energyWh`, `rssi`.
    - Store unit, type, allowed range, and display name.
    - Use it for validation and UI metadata.

12. Add an endpoint to list metrics available for a device.
    - Example: `GET /telemetry/devices/{deviceId}/metrics`.
    - Useful for charts and debugging.

13. Add source/device validation on consumed events.
    - Do not blindly trust `TelemetryReceivedEvent`.
    - Verify that `sourceId` is registered for `deviceId`, or document that
      ingestion-service is the only trust boundary.
    - Decide whether telemetry-service should fail closed or record quality
      errors when source metadata is missing.

14. Improve out-of-order handling.
    - Detect readings arriving older than the latest known timestamp.
    - Decide whether to accept, flag, or reject.
    - Important for buffered edge collectors and offline backfill.

15. Improve logging and metrics.
    - Count consumed events.
    - Count inserted rows.
    - Count duplicate rows.
    - Count invalid metrics.
    - Track processing latency from event timestamp to persistence.
    - Expose metrics by topic and outcome.

16. Add consumer lag monitoring.
    - Track Redpanda/Kafka consumer lag for `telemetry.raw.v1`.
    - Alert when lag grows beyond threshold.

17. Add schema compatibility checks for Kafka events.
    - Define event versioning policy.
    - Keep compatibility between ingestion-service producers and
      telemetry-service consumers.
    - Consider schema registry later if event contracts grow.

### Medium

18. Remove or complete legacy telemetry tables.
    - `telemetry_readings`, `telemetry_quality_flags`, and
      `telemetry_aggregates` exist, but the active path mostly uses
      `telemetry.measurements`.
    - Either wire these tables into the current flow or remove them to avoid
      confusion.

19. Clarify timestamp semantics.
    - Define `recorded_at` as device reading time.
    - Define `created_at` as persistence time.
    - Add `received_at` if the system needs ingestion arrival time.

20. Add support for backfill/import use cases.
    - Allow historical telemetry imports with explicit source and quality.
    - Avoid triggering noisy real-time alerts for old backfilled data unless
      requested.

21. Add multi-tenant partitioning assumptions.
    - Current measurement rows contain `device_id`, but not `account_id`.
    - Decide whether TimescaleDB rows should also include `account_id` for
      faster authorization and tenant-level operations.

22. Add normalized raw payload storage policy.
    - Current measurement repository writes normalized metric rows only.
    - Decide whether raw payload should be stored, where, and for how long.
    - Avoid storing sensitive vendor payloads unnecessarily.

23. Add configurable metric limits.
    - Maximum metrics per event.
    - Maximum events per device per minute.
    - Maximum accepted time range for chart queries.

24. Add API pagination or cursoring for readings.
    - Current chart endpoint is enough for UI charts but not for export or
      detailed inspection.
    - Add cursor-based export endpoints if needed.

25. Add service documentation.
    - Document input event contract.
    - Document output event contract.
    - Document TimescaleDB schema.
    - Document metric naming conventions.
    - Document quality flag meanings.

## Ingestion Service

The current `ingestion-service` is the HTTP entry point for telemetry readings.
It validates incoming readings, checks that the referenced device data source
exists, applies idempotency, and publishes `TelemetryReceivedEvent` messages to
Redpanda topic `telemetry.raw.v1`. It is currently stateless and has no
PostgreSQL database of its own.

### Critical

1. Add source or device authentication for ingestion.
   - Current issue: real collectors/devices should not rely on a normal user
     access token for high-frequency telemetry submission.
   - Add source/device ingestion tokens or another service credential model.
   - Store only token hashes.
   - Support token rotation and revocation.

2. Enforce ownership and source binding.
   - Current issue: ingestion checks that a data source exists for a device, but
     the authorization model for who may submit that source is not explicit.
   - Verify that the source token belongs to the submitted `sourceId`.
   - Verify that `sourceId` belongs to `deviceId`.
   - Verify that the device/source is active.

3. Define trust boundary between ingestion and telemetry.
   - `ingestion-service` should be the main external validation boundary for
     raw readings.
   - Document which validations happen in ingestion and which are repeated in
     telemetry for defense in depth.

4. Improve Kafka publish reliability.
   - Current issue: publish is fire-and-forget from the service perspective.
   - Handle producer send failure explicitly.
   - Return a clear error when the event cannot be accepted for delivery.
   - Consider waiting for broker ack before returning `202`.

5. Add tests for ingestion behavior.
   - Valid reading returns `202` and publishes `TelemetryReceivedEvent`.
   - Unknown data source returns `404`.
   - Invalid timestamp returns `400`.
   - Invalid metric name returns `400`.
   - Non-numeric metric returns `400`, except allowed fields such as
     `relayState`.
   - Duplicate reading is accepted idempotently and does not publish again.
   - Batch endpoint handles mixed valid/invalid input according to defined
     policy.

6. Define batch semantics.
   - Current issue: batch ingestion loops over single-reading ingestion.
   - Decide whether a batch is all-or-nothing or partial success.
   - Return accepted, duplicate, and rejected counts.
   - Include per-item errors when partial success is allowed.

### High

7. Add rate limiting and abuse protection.
   - Limit by source token.
   - Limit by device.
   - Limit by account.
   - Limit by IP for public endpoints.
   - Coordinate with gateway rate limits.

8. Add request size and batch size limits.
   - Maximum metrics per reading.
   - Maximum readings per batch.
   - Maximum request body size.
   - Maximum source id and metric name lengths.

9. Add metric schema validation.
   - Validate supported metric names.
   - Validate units and allowed value ranges.
   - Reject or flag negative values where invalid, for example
     `activePowerW < 0`.
   - Keep metric metadata shared with telemetry-service.

10. Improve timestamp policy.
    - Make accepted clock skew configurable.
    - Define maximum backfill age.
    - Define future timestamp policy.
    - Distinguish device recorded time from ingestion received time.

11. Add ingestion audit or operational history.
    - Decide whether `ingestion-service` needs its own database or whether
      audit can be event/log based.
    - Track accepted, rejected, duplicate, and failed publish outcomes.
    - Include reason codes and correlation ids.

12. Add DLQ or failed-publish handling.
    - If Kafka is unavailable, decide whether to reject requests, buffer
      locally, or write to a durable outbox.
    - For production, consider an outbox table or durable queue if data loss is
      unacceptable.

13. Add backpressure policy.
    - When Redpanda or downstream telemetry processing is unhealthy, ingestion
      should avoid accepting unlimited data.
    - Return `429` or `503` where appropriate.
    - Communicate retry-after hints to collectors.

14. Improve idempotency storage and semantics.
    - Current Redis TTL is `PT24H`.
    - Define the deduplication window.
    - Decide whether idempotency key should include metric set hash, request id,
      or event id.
    - Track duplicate metrics for observability.

15. Add collector-friendly response contract.
    - Include `acceptedCount`, `duplicateCount`, `rejectedCount`, and optional
      per-reading status for batch requests.
    - Include retry guidance for temporary failures.

16. Add observability metrics.
    - Count accepted readings.
    - Count rejected readings by reason.
    - Count duplicate readings.
    - Count Kafka publish failures.
    - Track validation latency and publish latency.
    - Track batch sizes.

17. Add structured logs.
    - Include correlation id.
    - Include device id and source id when safe.
    - Include validation failure reason.
    - Avoid logging full metric payloads by default.

### Medium

18. Decide whether ingestion needs its own persistent store.
    - It can remain stateless for MVP.
    - Add storage if required for source tokens, ingestion audit, import jobs,
      outbox, or DLQ metadata.
    - Avoid duplicating telemetry time-series data in ingestion.

19. Add source token management endpoints or delegate to device-service.
    - Decide whether source credentials belong in `device-service` or a
      dedicated ingestion credential store.
    - Add create, rotate, revoke, and list-last-used operations.

20. Add CSV/import ingestion flow.
    - Support historical imports separately from real-time ingestion.
    - Track import job status.
    - Avoid triggering noisy real-time alerts for historical backfills unless
      explicitly requested.

21. Add MQTT/collector integration contract.
    - Document how future collectors should submit readings.
    - Define source token usage.
    - Define retry and batching expectations.

22. Add OpenAPI documentation.
    - Document `/ingestion/readings`.
    - Document `/ingestion/readings/batch`.
    - Document validation errors.
    - Document metric naming rules and examples.

23. Add performance tests.
    - Single reading throughput.
    - Batch ingestion throughput.
    - Redis idempotency cost.
    - Kafka publish latency.
    - Behavior when Redpanda is slow or unavailable.

24. Add configuration documentation.
    - `app.kafka.publish-enabled`.
    - `app.idempotency.redis-enabled`.
    - `app.idempotency.ttl`.
    - `app.device-service.base-url`.
    - Future rate limit and batch size properties.

## Device Service

The current `device-service` is the inventory and registry service for
VoltCanary. It owns accounts in the device domain, locations, devices, device
types, and data sources. It also contains early database models for sensors and
device analytics configuration, but those parts are not fully exposed through
the API yet.

### Critical

1. Add `/accounts/**` route to the API Gateway if the frontend or clients need
   `/accounts/current`.
   - Current issue: `device-service` exposes `GET /accounts/current`, but the
     gateway routes only `/devices/**`, `/locations/**`, and `/device-types/**`
     to this service.
   - File: `backend/services/api-gateway/src/main/resources/application.yml`
   - File: `backend/services/api-gateway/src/main/resources/application-local.yml`

2. Protect internal device-service endpoints.
   - Current issue:
     `/internal/devices/{deviceId}/data-sources/{sourceId}/exists` is used by
     `ingestion-service`, but it has no service-to-service authentication.
   - Add an internal service token, mTLS, network policy, or another explicit
     service authentication mechanism.
   - Keep the endpoint inaccessible from public gateway routes.

3. Define the source credential model.
   - Current issue: `data_sources` stores a source id, source type, and
     protocol, but not real ingestion credentials.
   - Add source/device ingestion tokens or references to credentials.
   - Store token hashes only.
   - Support token rotation and revocation.
   - Decide whether credentials belong in `device-service`,
     `ingestion-service`, or a dedicated credential service.

4. Complete ownership checks used by other services.
   - Current issue: `device-service` enforces ownership for its own device and
     location APIs, but telemetry, analytics, and alerts currently rely on
     device ids without a shared ownership contract.
   - Add an internal endpoint for ownership checks, or publish account/device
     ownership events so other services can enforce access locally.
   - Document which services must call device-service before returning data.

5. Replace duplicated JWT validation with a shared contract.
   - Current issue: `device-service` parses and verifies JWTs itself using the
     same shared secret as identity and gateway.
   - Prefer gateway-provided user headers plus trusted internal networking, or
     a shared security library with consistent JWT validation.
   - Ensure direct service access is not accidentally exposed without the same
     auth rules.

6. Implement the analytics config endpoints or remove them from public API.
   - Current issue:
     `GET/PATCH /devices/{deviceId}/analytics-config` return placeholder
     messages.
   - Implement read/update against `device_analytics_configs`.
   - Enforce ownership.
   - Validate config shape.
   - Return `404` or default config consistently when missing.

7. Add tests for the critical device flows.
   - Account is created or returned for the current user.
   - Location CRUD is scoped to the current account.
   - Device CRUD is scoped to the current account.
   - Unknown device type returns `400`.
   - Foreign device access returns `404` or `403` by policy.
   - Data source creation requires owned device.
   - Internal data source existence endpoint works for existing and missing
     source ids.
   - Gateway route coverage includes all intended public device-service paths.

### High

8. Improve the `data_sources` domain model.
   - Current issue: `name` is used as `externalId/sourceId`, and
     `connection_ref` is used as protocol.
   - Add explicit columns such as `source_id`, `source_type`, `protocol`,
     `connection_ref`, `enabled`, `status`, `last_seen_at`, and
     `credentials_ref`.
   - Add a unique constraint for `(device_id, source_id)`.

9. Add full data source API.
   - Current issue: only `POST /devices/{deviceId}/data-sources` exists.
   - Add list, get, patch, delete/disable endpoints.
   - Enforce ownership for all operations.
   - Prevent deleting active sources if telemetry still depends on them, or
     define archival behavior.

10. Add sensor API and service layer.
    - Current issue: `sensors` table and repository exist, but there is no
      controller or service.
    - Add CRUD for sensors under a device.
    - Store sensor type, unit, metric mapping, and optional metadata.
    - Enforce device ownership.

11. Model device status explicitly.
    - Current issue: `devices.status` is a free-form string.
    - Replace with a controlled enum such as `ACTIVE`, `DISABLED`, `OFFLINE`,
      `DECOMMISSIONED`.
    - Add allowed transitions.
    - Ensure disabled/decommissioned devices cannot ingest new telemetry unless
      explicitly allowed.

12. Add device lifecycle operations.
    - Add enable/disable endpoints.
    - Add decommission/archive behavior.
    - Decide whether delete should hard-delete, soft-delete, or only archive.
    - Avoid losing historical telemetry unintentionally.

13. Add events for device registry changes.
    - Publish events when devices, locations, data sources, sensors, and
      analytics configs are created or changed.
    - Let telemetry, analytics, and alerts update local caches or authorization
      projections without synchronous calls for every request.
    - Define event versioning.

14. Add pagination, filtering, and search at backend level.
    - Current list endpoints return all rows for the account.
    - Add pagination for devices and locations.
    - Add filters for location, type, status, and text search.
    - Keep response shapes stable for frontend use.

15. Add stronger validation.
    - Normalize and validate device names.
    - Validate manufacturer and model lengths.
    - Validate source id format.
    - Validate supported protocols and source types.
    - Return consistent `400` errors for invalid input.

16. Add account lifecycle policy.
    - Current issue: account is created lazily in `device-service` on first
      access.
    - Decide whether identity-service should emit a user-created event and
      device-service should create the account asynchronously.
    - Decide how user deletion/disable affects device accounts.

17. Add audit logging.
    - Track device, location, source, and sensor create/update/delete actions.
    - Include user id, account id, correlation id, target id, and action type.
    - Avoid logging secrets or source tokens.

18. Add OpenAPI documentation for device-service.
    - Document account, location, device, type, data source, sensor, and
      analytics config endpoints.
    - Document auth requirements and error responses.
    - Document which endpoints are internal only.

### Medium

19. Consider using gateway-provided user headers.
    - Current issue: controllers manually parse the `Authorization` header.
    - If gateway is the trusted external boundary, downstream services can read
      `X-User-Id`, `X-User-Email`, and `X-User-Role` after the gateway strips
      spoofed incoming headers.
    - Keep direct-service local development behavior documented.

20. Prepare for asymmetric JWT verification if services keep validating tokens.
    - Move from shared `HS256` secret to public-key verification.
    - Support `kid` and key rotation.
    - Keep JWT claim requirements aligned with identity-service and gateway.

21. Add device metadata support.
    - Add optional metadata JSON for vendor-specific fields.
    - Keep indexed common fields as real columns.
    - Avoid putting credentials or secrets in metadata.

22. Add location hierarchy or richer location model if needed.
    - Support building, floor, room, zone, or tags if the product needs it.
    - Keep the MVP `locations` model simple until real UI requirements need
      hierarchy.

23. Add import/export support for device registry.
    - CSV import for bulk devices and sources.
    - Export inventory for operations and support.
    - Validate imports before applying them.

24. Add operational metrics.
    - Count devices by status and type.
    - Count data sources by protocol and status.
    - Track internal validation request latency.
    - Track failed ownership checks and rejected operations.

25. Clean up unused scaffolding packages.
    - Empty `.gitkeep` packages such as `common`, `config`, `domain`, `dto`,
      `exception`, `mapper`, `repository`, and `service` can be removed once the
      real package structure is clear.
    - Avoid keeping placeholder packages that make the service look more
      complete than it is.

## Analytics Service

The current `analytics-service` calculates baseline statistics, detects simple
rule-based anomalies, calculates risk score, stores recommendations and
predictions, and publishes analytics events for alerting. It is useful for MVP
demo flows, but it is not yet a fully automatic analytics pipeline.

### Critical

1. Add ownership and authorization checks for analytics reads and recalculation.
   - Current issue: `/analytics/devices/{deviceId}/...` accepts a device id but
     does not verify that the authenticated user owns or can access that device.
   - Call `device-service` for ownership checks, or maintain a local
     account/device authorization projection.
   - Enforce the same checks for `GET` endpoints and `POST /recalculate`.

2. Implement real handling of `telemetry.validated.v1`.
   - Current issue: `TelemetryValidatedConsumer` only logs the event payload and
     is disabled by default.
   - Deserialize `TelemetryValidatedEvent`.
   - Trigger incremental analytics or enqueue a recalculation job.
   - Define throttling/debouncing so every single telemetry point does not cause
     an expensive full recalculation.

3. Move recalculation out of synchronous HTTP request flow.
   - Current issue: `POST /analytics/devices/{deviceId}/recalculate` performs
     the full calculation inline.
   - Use `analytics_jobs` as a real job table.
   - Return job id and status.
   - Add job states such as `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`.
   - Add endpoints to query job status.

4. Add failure handling for telemetry-service calls.
   - Current issue: analytics depends on
     `/telemetry/devices/{deviceId}/readings`; failures can break recalculation.
   - Add timeout settings.
   - Handle `404`, `5xx`, and connection failures explicitly.
   - Return a clear error or mark the analytics job as failed with a reason.

5. Add failure handling and fallback for ML-service calls.
   - Current issue: `MlClient` has no fallback when ML service is unavailable.
   - Decide whether rule-based analytics can still complete without ML.
   - Store prediction status such as `AVAILABLE`, `UNAVAILABLE`, or `FAILED`.
   - Avoid failing the whole recalculation when optional ML inference is down.

6. Fix `RiskScoreChangedEvent.previousScore`.
   - Current issue: published risk events always set `previousScore` to `0`.
   - Read the previous latest risk score before saving the new one.
   - Publish both previous and current score.
   - Avoid noisy alerting when the score did not meaningfully change.

7. Add tests for the automatic analytics pipeline.
   - Consuming `TelemetryValidatedEvent` triggers the expected analytics action.
   - Invalid Kafka events are rejected or sent to DLQ.
   - Recalculation persists baseline, anomalies, risk score, recommendations,
     and prediction.
   - Kafka events are published with correct payloads.
   - Telemetry and ML failures are handled predictably.
   - Authorization prevents access to another user's device analytics.

### High

8. Replace hard-coded metric selection.
   - Current issue: analytics uses only `activePowerW` and a fixed `last_24h`
     period.
   - Support metric profiles per device type.
   - Support multiple metrics such as `voltageV`, `currentA`, `energyWh`,
     `rssi`, temperature, vibration, or device-specific metrics.
   - Read analytics configuration from `device-service` or a local config table.

9. Implement `FeatureSet` creation and `/features` endpoint.
   - Current issue: `feature_sets` table and repository exist, but feature
     generation is not wired.
   - Persist calculated feature windows.
   - Return recent feature sets through
     `GET /analytics/devices/{deviceId}/features`.
   - Include window start/end, metric names, and derived values.

10. Improve baseline storage model.
    - Current issue: baseline values are stored as opaque JSON.
    - Consider typed columns for common values such as metric, period, mean,
      median, p90, min, max, and sample count.
    - Keep JSON only for extensible model-specific details.
    - Add versioning for baseline calculation algorithms.

11. Preserve anomaly history.
    - Current issue: recalculation deletes all anomalies for a device before
      saving new ones.
    - Keep historical anomalies.
    - Add status or lifecycle fields such as `ACTIVE`, `SUPERSEDED`,
      `RESOLVED`, `EXPIRED`.
    - Avoid creating duplicate anomalies for the same condition in the same
      window.

12. Preserve recommendation history or lifecycle.
    - Current issue: recalculation deletes all recommendations for a device.
    - Add status such as `ACTIVE`, `DISMISSED`, `COMPLETED`, `SUPERSEDED`.
    - Allow users or future automation to mark recommendations as handled.

13. Add configurable rule engine for anomaly detection.
    - Current issue: rules are hard-coded in `AnalyticsRecalculationService`.
    - Move thresholds and rule enablement to configuration.
    - Support different thresholds per device type and metric.
    - Store rule id/version with every anomaly for traceability.

14. Split calculation logic into dedicated services.
    - Current issue: `AnalyticsRecalculationService` contains baseline,
      anomaly, risk, recommendation, prediction, DTO mapping, and JSON
      conversion logic.
    - Move baseline calculation to `BaselineCalculator`.
    - Move risk calculation to `RiskScoreCalculator`.
    - Add dedicated anomaly detector and recommendation generator classes.
    - Keep orchestration in `AnalyticsRecalculationService`.

15. Add idempotency for recalculation and event publication.
    - Avoid duplicate anomaly and risk events when the same window is processed
      repeatedly.
    - Track calculation window and input version.
    - Make published event ids stable or store an outbox.

16. Add transactional outbox for Kafka events.
    - Current issue: database writes and Kafka publishes are not atomic.
    - A failure after DB commit but before Kafka publish can lose alert events.
    - Store outgoing events in an outbox table and publish them reliably.

17. Add DLQ and retry handling for analytics consumers.
    - Configure retry policy for Kafka consumers.
    - Send repeatedly failing events to an analytics DLQ topic.
    - Include failure reason and original event metadata.

18. Improve risk score model.
    - Current issue: risk score is a simple sum of hard-coded anomaly weights.
    - Define score factors, weights, and caps explicitly.
    - Include data quality and sample size.
    - Store explanations with machine-readable reason codes.

19. Add multi-tenant data model assumptions.
    - Current analytics tables store `device_id` but not `account_id`.
    - Decide whether to denormalize `account_id` into analytics rows for faster
      authorization and tenant-level cleanup.
    - Keep ownership rules consistent with device-service.

20. Add retention policy for analytics data.
    - Define how long baselines, feature sets, predictions, anomalies, risk
      scores, jobs, and recommendations are kept.
    - Add cleanup jobs if required.
    - Avoid unbounded growth from frequent recalculations.

### Medium

21. Use a dedicated analytics database/schema.
    - Current local config uses `POSTGRES_DB=voltcanary`.
    - Decide whether analytics should use `voltcanary_analytics` or a separate
      schema.
    - Keep service ownership of tables clear.

22. Remove unused WebFlux dependency or switch to reactive client intentionally.
    - Current code uses synchronous `RestClient`, not `WebClient`.
    - Remove `spring-boot-starter-webflux` if it is not needed.
    - If reactive IO is desired, use it consistently and document why.

23. Add service-to-service authentication for telemetry and ML calls.
    - Analytics calls telemetry-service and ML-service directly.
    - Add internal service credentials, mTLS, or trusted network policy.
    - Avoid relying only on Kubernetes DNS isolation.

24. Add observability metrics.
    - Count recalculation jobs by status.
    - Track recalculation duration.
    - Track telemetry fetch failures.
    - Track ML inference failures and latency.
    - Count anomalies by type and severity.
    - Count published analytics events.

25. Add structured logs.
    - Include correlation id, device id, job id, and calculation window.
    - Avoid logging full telemetry payloads by default.
    - Include clear reason codes for failed calculations.

26. Add OpenAPI documentation.
    - Document analytics endpoints.
    - Document response shapes for baseline, anomalies, risk,
      recommendations, predictions, and jobs.
    - Document which endpoints are user-facing and which are internal.

27. Improve prediction result model.
    - Current prediction output is stored as opaque JSON.
    - Add typed columns for model version, anomaly score, degradation score,
      confidence, and risk level.
    - Store explanations separately or as structured JSON with versioning.

28. Add performance tests.
    - Test recalculation with small, medium, and large telemetry windows.
    - Measure telemetry-service query cost.
    - Measure database write volume.
    - Verify event publishing behavior under load.

## Alert Service

The current `alert-service` consumes analytics events, creates deduplicated
alerts, exposes alert read/action endpoints, and stores alert lifecycle events.
It already has a useful MVP alert workflow, but it is not yet a tenant-safe
notification and escalation service.

### Critical

1. Add ownership and authorization checks for alert APIs.
   - Current issue: `GET /alerts` returns the latest alerts globally, without
     verifying that the authenticated user can access the referenced devices.
   - Filter alerts by devices owned by the current user's account.
   - Enforce the same ownership checks for `GET /alerts/{id}`,
     `POST /alerts/{id}/ack`, and `POST /alerts/{id}/resolve`.
   - Use `device-service` ownership checks or a local account/device projection.

2. Add account or tenant reference to alerts.
   - Current issue: `alerts` stores `device_id` but not `account_id`.
   - Add `account_id` if the service should enforce authorization locally.
   - Populate it from device registry events or a trusted device-service lookup.
   - Index `(account_id, created_at)` and `(account_id, status, created_at)`.

3. Add unique constraint for deduplication.
   - Current issue: `deduplication_key` has an index but not a unique
     constraint.
   - Concurrent consumers or repeated events can still create duplicate alerts.
   - Add a unique constraint or implement database-level upsert behavior.

4. Implement retry and DLQ handling for Kafka consumers.
   - Current issue: bad or repeatedly failing events can poison consumer
     processing.
   - Add retry policy.
   - Add DLQ topics for `anomaly.detected.v1` and `risk-score.changed.v1`.
   - Store failure reason and original event metadata.

5. Add tests for alert authorization and tenant isolation.
   - User sees only alerts for devices in their account.
   - User cannot get, acknowledge, or resolve another user's alert.
   - Admin/support behavior is explicit if such roles are added.
   - Alert list filtering does not leak foreign device ids.

6. Add tests for deduplication under repeated events.
   - Same anomaly event creates only one alert.
   - Same deduplication key under concurrent handling creates only one alert.
   - Different hours or different severities create separate alerts by policy.
   - Resolved alert behavior on repeated events is defined and tested.

### High

7. Add filtering and pagination to `GET /alerts`.
   - Support filters by `deviceId`, `status`, `severity`, `alertType`, `from`,
     and `to`.
   - Add cursor or page-based pagination.
   - Keep default ordering by newest first.
   - Avoid returning only a hard-coded global top 100.

8. Implement notification delivery.
   - Current issue: `notification_channels` exists, but no notifications are
     sent.
   - Add delivery workers for chosen channels, for example email, webhook,
     Telegram, Slack, SMS, or push notifications.
   - Store delivery attempts and outcomes.
   - Avoid blocking Kafka consumers on slow external notification providers.

9. Add notification channel API.
   - Add create, list, update, disable, and delete endpoints.
   - Scope channels by account.
   - Validate channel configuration.
   - Store secrets through Vault or another secret store, not plain JSON in the
     database.

10. Add notification delivery status model.
    - Store notification attempts separately from alerts.
    - Track `PENDING`, `SENT`, `FAILED`, `RETRYING`, and `DISABLED`.
    - Store retry count, last error code, provider response summary, and sent
      time.

11. Add escalation policy.
    - Define what happens when `CRITICAL` or `WARNING` alerts are not
      acknowledged within a configured period.
    - Support repeated notifications, escalation to another channel, or higher
      priority.
    - Store escalation events in `alert_events`.

12. Improve deduplication strategy.
    - Current key is `deviceId:alertType:severity:hour`.
    - Decide whether deduplication should consider anomaly id, metric, window,
      rule id, or source id.
    - Define behavior when a new event arrives for an already resolved alert.
    - Move logic into `AlertDeduplicationService` instead of keeping it inside
      `AlertService`.

13. Add alert lifecycle actor fields.
    - Add `acknowledged_by`.
    - Add `resolved_by`.
    - Add `assigned_to` if manual operations are planned.
    - Include actor information in `alert_events`.

14. Standardize alert event payloads.
    - Current issue: `ACKNOWLEDGED` stores the whole `Alert`, while `RESOLVED`
      stores a custom resolution object.
    - Define a consistent event payload shape.
    - Include event id, alert id, actor id, action, timestamp, and reason when
      relevant.

15. Align severity taxonomy across services.
    - Analytics uses levels such as `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.
    - Alert service uses `INFO`, `WARNING`, `CRITICAL`.
    - Decide whether to add `HIGH` to alerts or keep the current mapping.
    - Document the mapping and keep frontend display consistent.

16. Add alert source and correlation fields.
    - Store source event id.
    - Store anomaly id or risk score id.
    - Store rule id or analytics calculation id when available.
    - This makes alert traceability and debugging easier.

17. Add idempotency by source event id.
    - Deduplication by hour prevents some duplicates but does not guarantee
      idempotent event handling.
    - Store consumed event ids or source event references.
    - Ignore events that were already processed.

18. Add alert reopen policy.
    - Define what happens when the same condition appears again after an alert
      was resolved.
    - Options: create a new alert, reopen the old alert, or append an event to
      the resolved alert.
    - Make this behavior explicit and test it.

19. Add notification preferences.
    - Allow account-level or user-level preferences.
    - Configure severities, quiet hours, channels, and device groups.
    - Ensure critical alerts can bypass quiet hours if required.

20. Add alert aggregation for dashboards.
    - Count open, acknowledged, and resolved alerts.
    - Count by severity and device.
    - Add endpoint for dashboard summary instead of forcing frontend to compute
      everything from raw alerts.

### Medium

21. Add service-to-service trust boundary documentation.
    - Alert service trusts events from analytics topics.
    - Document which producers are allowed to write those topics.
    - Add Kafka ACLs or deployment-level restrictions when moving beyond local
      development.

22. Add notification channel secrets handling.
    - `notification_channels.config_json` should not store raw secrets.
    - Store only non-secret config and a `credentials_ref`.
    - Put actual tokens/passwords/webhook secrets in Vault or another secret
      manager.

23. Add retention policy.
    - Define how long resolved alerts and alert events are kept.
    - Define whether old notification attempts are archived or deleted.
    - Add cleanup jobs if needed.

24. Add OpenAPI documentation.
    - Document alert list filters.
    - Document lifecycle actions.
    - Document error responses.
    - Document notification channel endpoints once implemented.

25. Add observability metrics.
    - Count alerts created by type and severity.
    - Count acknowledgements and resolutions.
    - Track time-to-ack and time-to-resolve.
    - Count Kafka consumer failures.
    - Count notification attempts, successes, failures, and retries.

26. Add structured logs.
    - Include correlation id, alert id, device id, source event id, and action.
    - Avoid logging full notification secrets or sensitive channel config.
    - Log deduplication decisions with clear reason codes.

27. Add admin/support workflows if needed.
    - Search alerts across accounts for support users.
    - Add role-based access checks.
    - Keep regular users scoped to their own account.

28. Clean up unused scaffolding.
    - `AlertDeduplicationService` should either be wired into real logic or
      removed until needed.
    - Empty placeholder packages should be cleaned up when the service package
      layout is standardized.

## Project Structure

The current repository layout works for MVP development, but the package and
folder structure should be normalized before the system grows further.

### Medium

1. Standardize service locations.
   - Keep all backend microservices under one predictable folder, for example
     `backend/services`.
   - Keep shared backend libraries under a separate folder, for example
     `backend/libs` or `backend/common`.
   - Avoid mixing deploy files, generated files, service code, and shared code
     in the same level.

2. Standardize Java package layout inside each service.
   - Use a consistent structure such as:
     - `controller`
     - `service`
     - `repository`
     - `domain` or `entity`
     - `dto`
     - `mapper`
     - `config`
     - `security`
     - `exception`
   - Avoid having each domain feature invent a different internal layout unless
     the service intentionally follows a feature-sliced architecture.

3. Decide between layered and feature-sliced package style.
   - Layered style example:
     `controller/DeviceController`, `service/DeviceService`,
     `repository/DeviceRepository`, `entity/Device`.
   - Feature-sliced style example:
     `device/DeviceController`, `device/DeviceService`,
     `device/DeviceRepository`, `device/Device`.
   - Pick one approach and apply it consistently across services.

4. Remove empty placeholder packages after the structure is chosen.
   - Empty `.gitkeep` packages make services look more complete than they are.
   - Keep only folders that contain real code or documented extension points.

5. Align test package structure with production code.
   - Integration tests should be easy to find.
   - Unit tests should mirror the class or feature they cover.
   - Shared test utilities should have a clear location.

6. Add a short repository structure document.
   - Document where new services go.
   - Document where shared libraries go.
   - Document where API docs, architecture docs, deployment manifests, and
     scripts belong.
   - This should prevent the layout from drifting as more services are added.

## Kubernetes And Deployment

The current Kubernetes manifests are useful for local development and learning,
but they still run the system in a development-style mode. Before production,
the deployment model should be hardened and made more explicit.

### Critical

1. Build real Docker images for every service.
   - Current issue: Java services run from `maven:3.9-eclipse-temurin-21` and
     build source code inside the Kubernetes pod.
   - ML service installs Python dependencies at container startup.
   - Frontend installs/runs dev server inside the pod.
   - Expected behavior: CI builds versioned images, pushes them to a registry,
     and Kubernetes only pulls and runs immutable images.

2. Remove `hostPath` source-code mounts from non-local environments.
   - Current local overlay mounts Windows folders into pods.
   - This is acceptable for local development only.
   - Production/dev cluster overlays should use container images, not host
     source directories.

3. Move secrets out of plain YAML.
   - Current issue: passwords and `JWT_SECRET` are stored in repository YAML.
   - Use Vault, External Secrets Operator, Sealed Secrets, SOPS, or another
     secret management mechanism.
   - Keep local-only default secrets clearly marked as unsafe.

4. Add Ingress or Gateway API configuration.
   - Current local access depends on `kubectl port-forward`.
   - Add a proper external entry point for API Gateway and frontend.
   - Define hostnames, TLS termination, and routing rules.

5. Add NetworkPolicies.
   - Restrict which services can talk to databases, Redpanda, MinIO, and
     internal service endpoints.
   - Keep internal endpoints inaccessible from public traffic.
   - Explicitly allow only required service-to-service paths.

6. Split Kubernetes overlays by environment.
   - Keep `local` for Docker Desktop development.
   - Add `dev`, `stage`, and `prod` overlays when needed.
   - Avoid putting HA/production assumptions into local-only configuration
     unless they are intentionally used for learning.

### High

7. Replace runtime Maven/Python/npm installs with CI build pipeline.
   - Build backend JARs in CI.
   - Build frontend static assets in CI.
   - Build ML image with dependencies baked in.
   - Run tests before publishing images.

8. Add image tags and image pull policy strategy.
   - Use immutable tags such as git SHA or release version.
   - Avoid relying on `latest`.
   - Define when `imagePullPolicy` should be `IfNotPresent` or `Always`.

9. Add production-grade resource requests and limits.
   - Current overlay applies generic CPU/memory values to all deployments.
   - Tune per service based on actual behavior.
   - Give databases and Redpanda separate resource planning.

10. Revisit HPA usage.
    - Current HPA requires metrics-server and meaningful resource requests.
    - Verify that local cluster has metrics-server if HPA is expected to work.
    - Decide which services should scale horizontally and which should not.
    - Avoid scaling consumers without understanding Kafka partitioning and
      idempotency.

11. Add database migration strategy.
    - Current services run Flyway on application startup.
    - Decide whether migrations should run as separate Kubernetes Jobs.
    - Prevent multiple replicas from racing on startup migrations.

12. Add persistent storage classes and backup policy.
    - PostgreSQL, TimescaleDB, Redis, Redpanda, and MinIO use persistent data.
    - Define storage class assumptions.
    - Add backup/restore procedures for stateful components.

13. Add readiness dependencies carefully.
    - Services currently rely on probes and retry behavior.
    - Document startup order expectations.
    - Avoid brittle hard dependencies where services should tolerate temporary
      downstream unavailability.

14. Add rollout and rollback documentation.
    - Document `kubectl rollout status`.
    - Document `kubectl rollout undo`.
    - Document how to inspect failed pods, logs, events, and probes.

15. Add Kubernetes Dashboard access documentation.
    - Document whether Dashboard is installed.
    - Document how to create a local admin ServiceAccount for learning.
    - Document how to create a short-lived token with `kubectl create token`.
    - Keep this local-only and do not use broad admin tokens in production.

### Medium

16. Add namespace and object naming conventions.
    - Keep naming predictable across services.
    - Document labels such as `app`, `component`, and `part-of`.

17. Add common labels and annotations.
    - Add labels for app name, service, version, environment, and ownership.
    - Useful for observability, selectors, cost tracking, and operations.

18. Add Prometheus/Grafana deployment or integration notes.
    - Services expose `/actuator/prometheus`.
    - Document how metrics should be scraped in Kubernetes.
    - Add ServiceMonitor resources if using Prometheus Operator.

19. Add centralized logging guidance.
    - Define where pod logs go.
    - Add correlation id expectations.
    - Document how to inspect logs locally and in real clusters.

20. Add local Kubernetes troubleshooting guide.
    - Common commands: `get pods`, `describe pod`, `logs`, `exec`,
      `port-forward`, `rollout status`.
    - Common failures: image pull, bad volume mount, failed readiness probe,
      CrashLoopBackOff, pending PVC.

21. Add cleanup/reset documentation.
    - Document how to delete the local stack.
    - Document how to delete PVCs when a full data reset is intended.
    - Warn that deleting PVCs removes database data.
