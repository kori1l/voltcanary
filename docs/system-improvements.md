# System Improvements Backlog

This document tracks service-level and system-level fixes that should be
addressed as VoltCanary moves from MVP scaffolding toward a production-ready
system.

## Identity Service

The current `identity-service` is a lightweight custom authentication service
for MVP use. It covers registration, login, access token issuance, refresh
tokens, and basic login audit. It is not yet a full Keycloak replacement.

### Critical

1. ~~Add `/auth/refresh` to the API Gateway public paths.~~
   - ~~Current issue: once an access token expires, the gateway requires a valid
     bearer token before allowing the refresh request.~~
   - ~~File: `backend/services/api-gateway/src/main/java/com/voltcanary/gateway/filter/JwtAuthenticationGlobalFilter.java`~~

2. ~~Implement real logout.~~
   - ~~Current issue: `POST /auth/logout` returns a placeholder message.~~
   - ~~Expected behavior: revoke the provided refresh token by setting
     `refresh_tokens.revoked_at`.~~

3. ~~Add brute-force protection for login.~~
   - ~~Add rate limits by email and IP.~~
   - ~~Add temporary lockout after repeated failed attempts.~~
   - ~~Keep the public error message generic.~~

4. ~~Add user status.~~
   - ~~Add a `status` field to `users`, for example `ACTIVE`, `DISABLED`,
     `LOCKED`, `PENDING_VERIFICATION`.~~
   - ~~Login and refresh must reject disabled or locked users.~~

5. ~~Check user status during refresh.~~
   - ~~Current issue: refresh token validity is checked, but user status is not
     part of the decision.~~

6. ~~Add tests for the auth flow.~~
   - ~~Registration success.~~
   - ~~Duplicate email.~~
   - ~~Weak password rejected.~~
   - ~~Login success.~~
   - ~~Wrong password audit.~~
   - ~~Refresh success.~~
   - ~~Revoked refresh token rejected.~~
   - ~~Expired refresh token rejected.~~
   - ~~`/auth/me` with valid, invalid, and expired access token.~~
   - ~~Logout revokes refresh token.~~

7. ~~Ensure the gateway does not trust incoming `X-User-*` headers.~~
   - ~~The gateway should overwrite or remove incoming `X-User-Id`,
     `X-User-Email`, and `X-User-Role` headers before forwarding requests.~~

### High

8. ~~Add `logout-all` support.~~
   - ~~Add an endpoint such as `POST /auth/logout-all`.~~
   - ~~Revoke all active refresh tokens for the current user.~~

9. ~~Add repository support for active user refresh tokens.~~
   - ~~Add methods such as `findByUserIdAndRevokedAtIsNull`.~~
   - ~~Needed for logout-all and session management.~~

10. ~~Add cleanup for expired refresh tokens.~~
    - ~~Use a scheduled job or maintenance command to remove or archive expired
      refresh tokens.~~

11. ~~Strengthen access token claims.~~
    - ~~Add `iss`.~~
    - ~~Add `aud`.~~
    - ~~Add `jti`.~~
    - ~~Add an explicit token type claim, for example `typ=access`.~~

12. ~~Improve refresh token rotation safety.~~
    - ~~Detect reuse of an already revoked refresh token.~~
    - ~~On reuse, revoke all refresh tokens for that user.~~

13. ~~Improve login audit.~~
    - ~~Store IP address.~~
    - ~~Store user agent.~~
    - ~~Store correlation/request id.~~
    - ~~Store normalized failure category.~~

14. ~~Add change password endpoint.~~
    - ~~Add `POST /auth/change-password`.~~
    - ~~Require current password.~~
    - ~~Revoke all refresh tokens after a successful password change.~~

15. ~~Add integration tests for gateway plus identity.~~
    - ~~`/auth/login` is public.~~
    - ~~`/auth/refresh` is public.~~
    - ~~Protected endpoint without token returns `401`.~~
    - ~~Protected endpoint with valid token succeeds.~~
    - ~~Protected endpoint with expired token returns `401`.~~

16. ~~Synchronize JWT validation code.~~
    - ~~JWT parsing and validation logic currently exists in multiple services.~~
    - ~~Prefer a shared internal library or a documented contract with consistent
      validation behavior.~~

### Medium

17. ~~Consider asymmetric JWT signing.~~
    - ~~Move from shared-secret `HS256` to `RS256` or `ES256`.~~
    - ~~Identity signs with a private key.~~
    - ~~Gateway and services verify with a public key.~~

18. ~~Prepare for key rotation.~~
    - ~~Add `kid` to JWT headers.~~
    - ~~Support active and previous verification keys.~~

19. ~~Add password reset flow.~~
    - ~~Add forgot-password request endpoint.~~
    - ~~Add one-time reset token with expiry.~~
    - ~~Add reset confirmation endpoint.~~
    - ~~Integrate email delivery later.~~

20. ~~Add email verification.~~
    - ~~Add `email_verified`.~~
    - ~~Add verification token and confirmation endpoint.~~
    - ~~Prevent or limit access for unverified users if required.~~

21. ~~Expand roles and permissions.~~
    - ~~Current model has a single string role, defaulting to `USER`.~~
    - ~~Define roles such as `USER`, `ADMIN`, and `SUPPORT`.~~
    - ~~Decide whether authorization is enforced in gateway, services, or both.~~

22. ~~Add admin endpoints.~~
    - ~~List users.~~
    - ~~Disable or lock a user.~~
    - ~~Change user role.~~
    - ~~View active refresh sessions.~~
    - ~~Revoke user sessions.~~

23. ~~Improve API error consistency.~~
    - ~~Keep login failure responses generic.~~
    - ~~Ensure internal details do not leak through public error responses.~~
    - ~~Keep detailed failure reasons only in audit logs.~~

24. ~~Document the auth contract.~~
    - ~~Endpoints.~~
    - ~~Access and refresh token TTL.~~
    - ~~JWT claims.~~
    - ~~Roles.~~
    - ~~Gateway-added downstream headers.~~
    - ~~Expected downstream authorization behavior.~~

## API Gateway

The current `api-gateway` is the external HTTP entry point for the frontend and
external clients. It routes requests to backend services, validates access JWTs,
adds user context headers, handles CORS, and attaches a correlation id.

### Critical

1. ~~Add `/auth/refresh` to public paths.~~
   - ~~Current issue: refresh requests require a valid access token at the
     gateway, which breaks refresh once the access token expires.~~
   - ~~File: `backend/services/api-gateway/src/main/java/com/voltcanary/gateway/filter/JwtAuthenticationGlobalFilter.java`~~

2. ~~Strip or overwrite incoming `X-User-*` headers before forwarding.~~
   - ~~Current issue: the gateway sets `X-User-Id`, `X-User-Email`, and
     `X-User-Role`, but it should explicitly prevent clients from spoofing
     those headers.~~
   - ~~Expected behavior: remove incoming identity headers first, then set values
     only from validated JWT claims.~~

3. ~~Validate required JWT claims.~~
   - ~~Current issue: the gateway checks signature and expiration, but does not
     enforce required claim presence beyond using them.~~
   - ~~Require non-empty `sub`.~~
   - ~~Require expected `typ=access` once identity-service adds it.~~
   - ~~Require expected `iss` and `aud` once those claims are introduced.~~

4. ~~Add tests for gateway authentication behavior.~~
   - ~~Public route without token succeeds for `/auth/login`.~~
   - ~~Public route without token succeeds for `/auth/register`.~~
   - ~~Public route without token succeeds for `/auth/refresh` after it is added.~~
   - ~~Protected route without token returns `401`.~~
   - ~~Protected route with malformed token returns `401`.~~
   - ~~Protected route with expired token returns `401`.~~
   - ~~Protected route with valid token forwards user headers.~~
   - ~~Spoofed incoming `X-User-*` headers are overwritten.~~

### High

5. ~~Centralize route definitions and remove duplication.~~
   - ~~Current issue: route definitions differ between `application.yml` and
     `application-local.yml`.~~
   - ~~Keep common route structure in the base config and override only target
     URIs or profile-specific values.~~

6. ~~Add route-level timeout policy.~~
   - ~~Define connect and response timeouts.~~
   - ~~Avoid requests hanging indefinitely when a downstream service is slow.~~
   - ~~Consider stricter timeouts for frontend-facing reads and longer timeouts
     for ingestion or analytics operations.~~

7. ~~Add circuit breaker or failure handling for downstream services.~~
   - ~~Use Spring Cloud Gateway circuit breaker support or a chosen resilience
     library.~~
   - ~~Return consistent `503` responses when a service is unavailable.~~
   - ~~Avoid cascading failures when a downstream service is overloaded.~~

8. ~~Add rate limiting.~~
   - ~~Apply stricter limits on `/auth/login`, `/auth/register`, and ingestion
     endpoints.~~
   - ~~Add general per-IP or per-user limits for protected routes.~~
   - ~~Decide whether Redis should back distributed rate limits.~~

9. ~~Define request size limits.~~
   - ~~Protect ingestion and auth endpoints from oversized bodies.~~
   - ~~Return consistent `413 Payload Too Large`.~~

10. ~~Improve CORS configuration.~~
    - ~~Current issue: CORS is configured both in YAML and in `CorsConfig`.~~
    - ~~Keep a single source of truth.~~
    - ~~Move allowed origins to environment/config properties.~~
    - ~~Support dev and production frontend origins explicitly.~~

11. ~~Add structured access logs.~~
    - ~~Keep correlation id, method, path, status, and duration.~~
    - ~~Add route id and downstream target service when available.~~
    - ~~Avoid logging tokens or sensitive request bodies.~~

12. ~~Add security audit logs.~~
    - ~~Log rejected authentication attempts at gateway level.~~
    - ~~Include reason category such as missing token, malformed token, expired
      token, invalid signature.~~
    - ~~Include correlation id.~~

13. ~~Add OpenAPI or route documentation.~~
    - ~~Document which external paths are exposed through the gateway.~~
    - ~~Document which service owns each path.~~
    - ~~Document which paths are public and which require authentication.~~

14. ~~Add health checks for downstream dependencies.~~
    - ~~Gateway health currently only reports its own process health.~~
    - ~~Add optional readiness checks or synthetic pings for key downstream
      services if useful for operations.~~

### Medium

15. ~~Prepare for asymmetric JWT verification.~~
    - ~~Move away from shared `HS256` secret in gateway.~~
    - ~~Verify tokens with public keys once identity-service signs with
      `RS256` or `ES256`.~~
    - ~~Support `kid`-based key selection.~~

16. ~~Add API versioning strategy.~~
    - ~~Decide whether public routes should use `/api/v1/...`.~~
    - ~~Avoid breaking frontend and external clients when service contracts
      evolve.~~

17. ~~Add role or permission enforcement where appropriate.~~
    - ~~Current gateway checks authentication but not authorization.~~
    - ~~Decide which route families require `ADMIN`, `SUPPORT`, or regular
      `USER`.~~
    - ~~Keep resource ownership checks in backend services.~~

18. ~~Add service-to-service boundary documentation.~~
    - ~~Clarify that external traffic goes through the gateway.~~
    - ~~Clarify that internal synchronous traffic should use internal service DNS.~~
    - ~~Clarify that asynchronous traffic should use Redpanda/Kafka.~~

19. ~~Add consistent error response shape.~~
    - ~~Gateway-generated errors should follow the same shape as service errors.~~
    - ~~Include correlation id in error responses.~~
    - ~~Avoid leaking internal target service details.~~

20. ~~Add metrics by route.~~
    - ~~Track request count, latency, status codes, and downstream failures by
      route id.~~
    - ~~Expose metrics through actuator/prometheus.~~

21. ~~Add production origin and TLS assumptions.~~
    - ~~Document whether TLS terminates at ingress, gateway, or load balancer.~~
    - ~~Ensure forwarded headers are handled correctly behind ingress.~~
    - ~~Configure trusted proxy behavior explicitly.~~

## Telemetry Service

The current `telemetry-service` consumes raw telemetry events from Redpanda,
normalizes numeric metrics, stores time-series measurements in TimescaleDB, and
publishes validated telemetry events for analytics.

### Critical

1. ~~Add ownership and authorization checks for telemetry reads.~~
   - ~~Current issue: `/telemetry/devices/{deviceId}/...` accepts a device id but
     does not verify that the authenticated user can access that device.~~
   - ~~Options:~~
     - ~~call `device-service` for ownership checks;~~
     - ~~use a replicated authorization model;~~
     - ~~enforce ownership in gateway plus service.~~

2. ~~Strengthen event validation before persistence.~~
   - ~~Validate required fields: `deviceId`, `sourceId`, `timestamp`, `metrics`.~~
   - ~~Reject or flag timestamps too far in the future.~~
   - ~~Reject or flag timestamps older than the allowed ingestion window.~~
   - ~~Reject unknown or unsupported metric names unless explicitly allowed.~~
   - ~~Validate metric value ranges per metric.~~

3. ~~Implement quality flag persistence and API.~~
   - ~~Current issue: `/telemetry/devices/{deviceId}/quality` returns an empty
     list.~~
   - ~~Persist quality flags for invalid, duplicate, normalized, out-of-order, or
     out-of-range readings.~~
   - ~~Return quality history through the existing quality endpoint.~~

4. ~~Add dead-letter handling for bad Kafka events.~~
   - ~~Current issue: malformed or repeatedly failing events can poison consumer
     processing.~~
   - ~~Add retry policy.~~
   - ~~Add `telemetry.raw.dlq.v1`.~~
   - ~~Include failure reason and original event metadata.~~

5. ~~Make idempotency behavior explicit.~~
   - ~~Current issue: duplicate metric rows are ignored at insert time, while
     duplicate events are only partially represented by quality flags.~~
   - ~~Define whether duplicate events should publish validated events.~~
   - ~~Track duplicate count and reason in quality data.~~

6. ~~Add tests for the telemetry pipeline.~~
   - ~~Raw event with numeric metrics creates measurement rows.~~
   - ~~Non-numeric metrics are ignored or flagged according to policy.~~
   - ~~`NaN` and infinite values are rejected or flagged.~~
   - ~~Duplicate events do not duplicate rows.~~
   - ~~Valid persisted event publishes `TelemetryValidatedEvent`.~~
   - ~~Invalid event goes to DLQ or records quality flags.~~
   - ~~`/latest` and `/readings` return expected data.~~

### High

7. ~~Use batch inserts for measurement persistence.~~
   - ~~Current issue: `TelemetryMeasurementRepository.insertAll` inserts rows one
     by one.~~
   - ~~Use JDBC batch updates for better throughput.~~
   - ~~Preserve duplicate handling.~~

8. ~~Add batch query and aggregation support for charts.~~
   - ~~Current `/readings` returns raw points with a hard `limit 1000`.~~
   - ~~Add downsampling or bucketed aggregation for large time ranges.~~
   - ~~Support interval parameters such as `1m`, `5m`, `1h`.~~

9. ~~Add TimescaleDB hypertable setup.~~
   - ~~Ensure `telemetry.measurements` is a hypertable.~~
   - ~~Add appropriate chunk interval.~~
   - ~~Verify indexes match query patterns.~~

10. ~~Add TimescaleDB retention and compression policy.~~
    - ~~Define how long raw measurements are kept.~~
    - ~~Compress older chunks.~~
    - ~~Keep aggregates longer than raw data if needed.~~

11. ~~Add metric registry or metric metadata.~~
    - ~~Define supported metrics such as `activePowerW`, `voltageV`, `currentA`,
      `energyWh`, `rssi`.~~
    - ~~Store unit, type, allowed range, and display name.~~
    - ~~Use it for validation and UI metadata.~~

12. ~~Add an endpoint to list metrics available for a device.~~
    - ~~Example: `GET /telemetry/devices/{deviceId}/metrics`.~~
    - ~~Useful for charts and debugging.~~

13. ~~Add source/device validation on consumed events.~~
    - ~~Do not blindly trust `TelemetryReceivedEvent`.~~
    - ~~Verify that `sourceId` is registered for `deviceId`, or document that
      ingestion-service is the only trust boundary.~~
    - ~~Decide whether telemetry-service should fail closed or record quality
      errors when source metadata is missing.~~

14. ~~Improve out-of-order handling.~~
    - ~~Detect readings arriving older than the latest known timestamp.~~
    - ~~Decide whether to accept, flag, or reject.~~
    - ~~Important for buffered edge collectors and offline backfill.~~

15. ~~Improve logging and metrics.~~
    - ~~Count consumed events.~~
    - ~~Count inserted rows.~~
    - ~~Count duplicate rows.~~
    - ~~Count invalid metrics.~~
    - ~~Track processing latency from event timestamp to persistence.~~
    - ~~Expose metrics by topic and outcome.~~

16. ~~Add consumer lag monitoring.~~
    - ~~Track Redpanda/Kafka consumer lag for `telemetry.raw.v1`.~~
    - ~~Alert when lag grows beyond threshold.~~

17. ~~Add schema compatibility checks for Kafka events.~~
    - ~~Define event versioning policy.~~
    - ~~Keep compatibility between ingestion-service producers and
      telemetry-service consumers.~~
    - ~~Consider schema registry later if event contracts grow.~~

### Medium

18. ~~Remove or complete legacy telemetry tables.~~
    - ~~`telemetry_readings` and `telemetry_quality_flags` are wired into the
      active flow; the unused `telemetry_aggregates` table and repository were
      removed.~~

19. ~~Clarify timestamp semantics.~~
    - ~~`recorded_at` is device reading time.~~
    - ~~`created_at` is telemetry-service persistence time.~~
    - ~~`received_at` is ingestion arrival time propagated from the raw event.~~

20. ~~Add support for backfill/import use cases.~~
    - ~~Historical telemetry is accepted only from explicit import/backfill
      source prefixes.~~
    - ~~Backfilled data is stored and quality-flagged, but does not publish
      realtime `TelemetryValidatedEvent` messages.~~

21. ~~Add multi-tenant partitioning assumptions.~~
    - ~~Telemetry tables include nullable `account_id` columns and account-first
      indexes so the event contract can be extended without reshaping storage.~~

22. ~~Add normalized raw payload storage policy.~~
    - ~~`telemetry_readings.metrics_json` stores only supported telemetry metric
      keys. Unsupported vendor payload fields are not persisted.~~

23. ~~Add configurable metric limits.~~
    - ~~Maximum metrics per event.~~
    - ~~Maximum events per device per minute.~~
    - ~~Maximum accepted time range for chart queries.~~

24. ~~Add API pagination or cursoring for readings.~~
    - ~~Current chart endpoint remains UI-oriented.~~
    - ~~Added cursor-based `/telemetry/devices/{deviceId}/readings/export`
      endpoint for detailed inspection and exports.~~

25. ~~Add service documentation.~~
    - ~~Document input event contract.~~
    - ~~Document output event contract.~~
    - ~~Document TimescaleDB schema.~~
    - ~~Document metric naming conventions.~~
    - ~~Document quality flag meanings.~~

## Ingestion Service

The current `ingestion-service` is the HTTP entry point for telemetry readings.
It validates incoming readings, checks that the referenced device data source
exists, applies idempotency, and publishes `TelemetryReceivedEvent` messages to
Redpanda topic `telemetry.raw.v1`. It is currently stateless and has no
PostgreSQL database of its own.

### Critical

1. ~~Add source or device authentication for ingestion.~~
   - ~~Collectors use source ingestion tokens resolved through device-service.~~
   - ~~Only token hashes are compared in ingestion.~~
   - ~~Rotation and revocation are delegated to data-source management.~~

2. ~~Enforce ownership and source binding.~~
   - ~~Verify that the source token belongs to the submitted `sourceId`.~~
   - ~~Verify that the source token belongs to the submitted `deviceId`.~~
   - ~~Verify that the device/source is active and protocol-compatible.~~

3. ~~Define trust boundary between ingestion and telemetry.~~
   - ~~`ingestion-service` is documented as the main external validation
     boundary.~~
   - ~~Telemetry-service repeats defensive validation before persistence.~~

4. ~~Improve Kafka publish reliability.~~
   - ~~Producer send failures are handled explicitly.~~
   - ~~The service waits for broker ack before returning `202`.~~
   - ~~Publish failure returns `503` and releases the idempotency key for retry.~~

5. ~~Add tests for ingestion behavior.~~
   - ~~Valid reading returns `202` and publishes `TelemetryReceivedEvent`.~~
   - ~~Unknown data source returns `404`.~~
   - ~~Invalid timestamp returns `400`.~~
   - ~~Invalid metric name returns `400`.~~
   - ~~Non-numeric metric returns `400`, except allowed fields such as
     `relayState`.~~
   - ~~Duplicate reading is accepted idempotently and does not publish again.~~
   - ~~Batch endpoint handles mixed valid/invalid input with partial success.~~

6. ~~Define batch semantics.~~
   - ~~Batch ingestion uses partial-success semantics.~~
   - ~~Responses include accepted, duplicate, and rejected counts.~~
   - ~~Responses include per-item status and error reason.~~

### High

7. ~~Add rate limiting and abuse protection.~~
   - ~~Limit by source.~~
   - ~~Limit by device.~~
   - ~~Limit by account.~~
   - ~~Limit by IP for public endpoints.~~
   - ~~Gateway also has an ingestion-specific rate-limit bucket.~~

8. ~~Add request size and batch size limits.~~
   - ~~Maximum metrics per reading.~~
   - ~~Maximum readings per batch.~~
   - ~~Maximum source id and metric name lengths.~~

9. ~~Add metric schema validation.~~
   - ~~Validate supported metric names.~~
   - ~~Validate allowed value ranges.~~
   - ~~Reject negative values where invalid, for example
     `activePowerW < 0`.~~

10. ~~Improve timestamp policy.~~
    - ~~Accepted clock skew is configurable.~~
    - ~~Maximum backfill age is configurable.~~
    - ~~Future timestamp policy is configurable.~~
    - ~~`timestamp` remains device recorded time; `TelemetryReceivedEvent.receivedAt`
      is ingestion receive time.~~

11. ~~Add ingestion audit or operational history.~~
    - ~~MVP keeps ingestion stateless and uses bounded in-memory audit events
      plus structured logs and metrics.~~
    - ~~Track accepted, rejected, duplicate, and failed publish outcomes.~~
    - ~~Include reason codes and correlation ids.~~

12. ~~Add DLQ or failed-publish handling.~~
    - ~~If Kafka is unavailable, ingestion rejects the request with `503`.~~
    - ~~The idempotency marker is removed on failed publish so collectors can
      retry.~~
    - ~~A durable outbox remains a later production hardening option.~~

13. ~~Add backpressure policy.~~
    - ~~When Kafka publish fails, ingestion opens a short backpressure window.~~
    - ~~Return `429` for rate limits and `503` for broker backpressure.~~
    - ~~Collectors receive retryable temporary failures.~~

14. ~~Improve idempotency storage and semantics.~~
    - ~~Deduplication window is configurable.~~
    - ~~Idempotency key includes device id, source id, timestamp, and metric-set
      hash.~~
    - ~~Duplicate readings are counted through metrics and response counts.~~

15. ~~Add collector-friendly response contract.~~
    - ~~Include `acceptedCount`, `duplicateCount`, `rejectedCount`, and
      per-reading status for batch requests.~~
    - ~~Temporary Kafka failures return `503` and are retryable.~~

16. ~~Add observability metrics.~~
    - ~~Count accepted readings.~~
    - ~~Count rejected readings.~~
    - ~~Count duplicate readings.~~
    - ~~Count Kafka publish failures.~~
    - ~~Track ingestion and publish latency.~~
    - ~~Track batch sizes.~~

17. ~~Add structured logs.~~
    - ~~Include correlation id.~~
    - ~~Include device id and source id when safe.~~
    - ~~Avoid logging full metric payloads by default.~~

### Medium

18. ~~Decide whether ingestion needs its own persistent store.~~
    - ~~It remains stateless for MVP.~~
    - ~~Source tokens stay in device-service; audit is log/metric/in-memory
      based; no telemetry time-series data is duplicated in ingestion.~~

19. ~~Add source token management endpoints or delegate to device-service.~~
    - ~~Source credentials are delegated to `device-service`.~~
    - ~~Create, rotate, revoke, and last-used operations belong to data-source
      management.~~

20. ~~Add CSV/import ingestion flow.~~
    - ~~Historical imports are handled separately from realtime ingestion through
      `/ingestion/imports/readings` and `/ingestion/imports/readings/batch`.~~
    - ~~Import job persistence is deferred until a durable ingestion store is
      introduced.~~
    - ~~Backfilled data should use explicit import/backfill source identities so
      downstream services can avoid noisy realtime alerting.~~

21. ~~Add MQTT/collector integration contract.~~
    - ~~Document how collectors submit HTTP and MQTT readings.~~
    - ~~Define source token usage.~~
    - ~~Define retry and batching expectations.~~

22. ~~Add OpenAPI documentation.~~
    - ~~Document `/ingestion/readings`.~~
    - ~~Document `/ingestion/readings/batch`.~~
    - ~~Document validation errors.~~
    - ~~Document metric naming rules and examples.~~

23. ~~Add performance tests.~~
    - ~~Single reading and batch ingestion smoke budgets are covered by
      `IngestionPerformanceSmokeTest`.~~
    - ~~Kafka publish latency and failure behavior are covered by publisher
      acknowledgement handling and Micrometer latency metrics.~~

24. ~~Add configuration documentation.~~
    - ~~Document Kafka, idempotency, device-service, rate-limit, backpressure,
      batch size, metric size, and import-related properties.~~

## Device Service

The current `device-service` is the inventory and registry service for
VoltCanary. It owns accounts in the device domain, locations, devices, device
types, and data sources. It also contains early database models for sensors and
device analytics configuration, but those parts are not fully exposed through
the API yet.

### Critical

1. ~~Add `/accounts/**` route to the API Gateway if the frontend or clients need
   `/accounts/current`.~~
   - ~~`/accounts/**` now routes to `device-service`.~~
   - ~~File: `backend/services/api-gateway/src/main/resources/application.yml`.~~

2. ~~Protect internal device-service endpoints.~~
   - ~~Internal data-source and ownership endpoints require
     `X-Internal-Service-Token`.~~
   - ~~Ingestion and telemetry clients send the same configured internal token.~~
   - ~~Public gateway routes do not expose `/internal/**`.~~

3. ~~Define the source credential model.~~
   - ~~Data sources store source id, source type, protocol, status, token hash,
     token prefix, token timestamps, and revocation state.~~
   - ~~Raw source tokens are returned only on rotation and are not persisted.~~
   - ~~Credential ownership remains in `device-service`.~~

4. ~~Complete ownership checks used by other services.~~
   - ~~Added `GET /internal/devices/{deviceId}/access?userId={userId}`.~~
   - ~~Telemetry-service uses this contract before returning device telemetry.~~
   - ~~Device API documentation lists internal ownership contract.~~

5. ~~Replace duplicated JWT validation with a shared contract.~~
   - ~~`device-service` now resolves users from gateway-provided
     `X-User-Id`, `X-User-Email`, and `X-User-Role` headers first.~~
   - ~~The old local HMAC JWT parser was removed. Direct local bearer-token
     fallback uses the shared `common-security` JWT validator.~~
   - ~~Device tests exercise the gateway-header contract.~~

6. ~~Implement the analytics config endpoints or remove them from public API.~~
   - ~~`GET/PATCH /devices/{deviceId}/analytics-config` read and update
     `device_analytics_configs`.~~
   - ~~Ownership is enforced through `DeviceService.findOwned`.~~
   - ~~Missing config returns a default enabled config.~~

7. ~~Add tests for the critical device flows.~~
   - ~~Account is created or returned for the current user.~~
   - ~~Location CRUD is scoped to the current account.~~
   - ~~Device CRUD is scoped to the current account.~~
   - ~~Foreign device access returns `404`.~~
   - ~~Data source creation requires owned device.~~
   - ~~Internal data source endpoints require token and expose token hash, not
     raw token.~~
   - ~~Internal ownership endpoint returns allow/deny decisions.~~
   - ~~Analytics config read/update is covered.~~

### High

8. ~~Improve the `data_sources` domain model.~~
   - ~~Added explicit `source_id`, `source_type`, `protocol`,
     `connection_ref`, `enabled`, `status`, `last_seen_at`, and
     `credentials_ref` columns.~~
   - ~~Backfilled `source_id/source_type/protocol` from legacy
     `name/type/connection_ref`.~~
   - ~~Added a unique index for `(device_id, source_id)`.~~

9. ~~Add full data source API.~~
   - ~~Added list, get, patch, and delete-as-disable endpoints under
     `/devices/{deviceId}/data-sources`.~~
   - ~~Ownership is enforced for all operations.~~
   - ~~Delete disables the source and revokes the token reference; historical
     telemetry is not hard-deleted.~~
   - ~~Device tests cover list/get/update/disable and foreign access.~~

10. ~~Add sensor API and service layer.~~
    - ~~Added CRUD under `/devices/{deviceId}/sensors`.~~
    - ~~Sensors store type, unit, metric mapping, optional metadata JSON, and
      timestamps.~~
    - ~~Ownership is enforced through the parent device.~~
    - ~~Device tests cover sensor create/list/update/delete and foreign access.~~

11. ~~Model device status explicitly.~~
    - ~~`devices.status` is mapped to `DeviceStatus`
      (`ACTIVE`, `DISABLED`, `OFFLINE`, `DECOMMISSIONED`).~~
    - ~~Lifecycle transitions prevent re-enabling decommissioned devices.~~
    - ~~Internal source existence checks return `false` for disabled or
      decommissioned devices, blocking normal ingestion validation.~~

12. ~~Add device lifecycle operations.~~
    - ~~Added `POST /devices/{id}/enable`, `/disable`, and `/decommission`.~~
    - ~~`DELETE /devices/{id}` now archives by setting `DECOMMISSIONED` instead
      of hard-deleting the device.~~
    - ~~Device tests cover lifecycle transitions and ingestion lookup behavior.~~

13. ~~Add events for device registry changes.~~
    - ~~Added `DeviceRegistryChangedEvent` in `common-events` and publisher for
      `device-registry.changed.v1`.~~
    - ~~Devices, locations, data sources, sensors, and analytics configs publish
      create/update/delete/status events.~~
    - ~~Events carry `eventVersion=v1`, account id, device id, resource id,
      resource type, action, status, and timestamp.~~
    - ~~Device tests verify event publishing for representative registry
      changes.~~

14. ~~Add pagination, filtering, and search at backend level.~~
    - ~~`GET /devices` supports `locationId`, `type`, `status`, `search`,
      `limit`, and `offset`.~~
    - ~~`GET /locations` supports `search`, `limit`, and `offset`.~~
    - ~~Response shape remains a JSON array for frontend compatibility.~~
    - ~~Device tests cover location and device filtering/pagination.~~

15. ~~Add stronger validation.~~
    - ~~Device and location names are trimmed before storage.~~
    - ~~Device `manufacturer` and `model` are limited to 255 characters.~~
    - ~~Data-source `externalId/sourceId` is limited to 128 characters and a
      safe identifier character set.~~
    - ~~Supported protocols and source types are enforced by enum binding.~~
    - ~~Invalid request bodies return existing consistent `400`
      `validation_failed` or `bad_request` errors.~~

16. ~~Add account lifecycle policy.~~
    - ~~Accounts now have `ACTIVE`, `DISABLED`, and `DELETED` lifecycle
      status.~~
    - ~~`GET /accounts/current` still lazily creates accounts, but disabled or
      deleted accounts receive `403 account_inactive`.~~
    - ~~Added internal lifecycle sync endpoint for identity-service:
      `POST /internal/accounts/users/{userId}/lifecycle`.~~
    - ~~Device tests cover disable/reactivate behavior.~~

17. ~~Add audit logging.~~
    - ~~Added structured audit logging for device, location, source, sensor,
      and analytics-config mutations.~~
    - ~~Audit logs include user id, account id, correlation id, target id, and
      action type.~~
    - ~~Source tokens and other secrets are not logged.~~
    - ~~Device tests verify representative audit calls.~~

18. ~~Add OpenAPI documentation for device-service.~~
    - ~~Added springdoc OpenAPI support with `/v3/api-docs` and
      `/swagger-ui.html`.~~
    - ~~Configured bearer auth and `X-Internal-Service-Token` security schemes.~~
    - ~~Updated `docs/api/device-api.md` with account, location, device, data
      source, sensor, analytics config, auth, internal, error, audit, and
      OpenAPI notes.~~

### Medium

19. ~~Consider using gateway-provided user headers.~~
    - ~~Controllers now receive request headers and resolve the current user via
      `AuthenticatedUserResolver`.~~
    - ~~Gateway-provided `X-User-Id`, `X-User-Email`, and `X-User-Role` are the
      primary downstream auth contract.~~
    - ~~Direct-service local development behavior is documented in
      `docs/api/device-api.md`.~~

20. ~~Prepare for asymmetric JWT verification if services keep validating
    tokens.~~
    - ~~Direct bearer-token fallback in `device-service` uses shared
      `common-security` public-key JWT validation.~~
    - ~~The validator supports `kid`, configured public keys, issuer, audience,
      token type, and standard claim checks aligned with gateway.~~
    - ~~The primary production contract remains gateway-provided user headers.~~

21. ~~Add device metadata support.~~
    - ~~Added `devices.metadata_json` and optional `metadata` on device
      create/update responses.~~
    - ~~Common indexed fields remain real columns (`manufacturer`, `model`,
      status, type, location).~~
    - ~~Metadata rejects obvious secret keys such as `password`, `token`,
      `secret`, and `credential`.~~
    - ~~Device tests cover metadata persistence and secret rejection.~~

22. ~~Add location hierarchy or richer location model if needed.~~
    - ~~Locations now support `parentId`, `building`, `floor`, `room`, `zone`,
      and `tags`.~~
    - ~~Parent locations are ownership-checked and self-parenting is rejected.~~
    - ~~Location search includes the richer hierarchy fields.~~

23. ~~Add import/export support for device registry.~~
    - ~~Added CSV device import through `POST /devices/imports/csv`.~~
    - ~~Added inventory export through `GET /devices/export.csv`.~~
    - ~~Added CSV data-source import/export under
      `/devices/{deviceId}/data-sources/imports/csv` and
      `/devices/{deviceId}/data-sources/export.csv`.~~
    - ~~Imports validate all rows before creating registry resources.~~

24. ~~Add operational metrics.~~
    - ~~Count devices by status and seeded type.~~
    - ~~Count data sources by protocol and status.~~
    - ~~Track internal validation request latency.~~
    - ~~Track failed ownership checks and rejected operations.~~

25. ~~Clean up unused scaffolding packages.~~
    - ~~No placeholder `.gitkeep` packages remain under the device-service Java
      package tree.~~
    - ~~The service now uses concrete domain package names instead of empty
      scaffold packages.~~

## Analytics Service

The current `analytics-service` calculates baseline statistics, detects simple
rule-based anomalies, calculates risk score, stores recommendations and
predictions, and publishes analytics events for alerting. It is useful for MVP
demo flows, but it is not yet a fully automatic analytics pipeline.

### Critical

1. ~~Add ownership and authorization checks for analytics reads and recalculation.~~
   - ~~Analytics endpoints now call `device-service` internal device access
     checks through `AnalyticsAccessService`.~~
   - ~~`GET` endpoints and `POST /recalculate` enforce the same checks using
     gateway-provided `X-User-*` headers.~~

2. ~~Implement real handling of `telemetry.validated.v1`.~~
   - ~~`TelemetryValidatedConsumer` now deserializes `TelemetryValidatedEvent`.~~
   - ~~Validated telemetry enqueues a recalculation job for the device.~~
   - ~~Active `PENDING` or `RUNNING` jobs are reused as a simple debounce so each
     telemetry point does not start a duplicate full recalculation.~~

3. ~~Move recalculation out of synchronous HTTP request flow.~~
   - ~~`POST /analytics/devices/{deviceId}/recalculate` now creates or returns
     an analytics job instead of running the full calculation inline.~~
   - ~~`analytics_jobs` tracks `PENDING`, `RUNNING`, `COMPLETED`, and `FAILED`.~~
   - ~~Added job list and job status endpoints under
     `/analytics/devices/{deviceId}/jobs`.~~

4. ~~Add failure handling for telemetry-service calls.~~
   - ~~`TelemetryClient` now applies a timeout and maps `404`, `5xx`, and
     connection failures to explicit service errors.~~
   - ~~Failed recalculation jobs are marked `FAILED` with an error message.~~

5. ~~Add failure handling and fallback for ML-service calls.~~
   - ~~`MlClient` now has timeout handling and returns no inference result when
     ML is unavailable.~~
   - ~~Rule-based analytics still completes and stores prediction JSON with
     `status=UNAVAILABLE` when optional ML inference is down.~~

6. ~~Fix `RiskScoreChangedEvent.previousScore`.~~
   - ~~Analytics now reads the previous latest risk score before saving the new
     one.~~
   - ~~Published risk events include previous and current scores, and unchanged
     scores are not published again.~~

7. ~~Add tests for the automatic analytics pipeline.~~
   - ~~Consuming `TelemetryValidatedEvent` triggers recalculation job enqueue.~~
   - ~~Invalid Kafka events are rejected by the consumer.~~
   - ~~Recalculation persists baseline, feature set, anomalies, risk score,
     recommendations, and prediction.~~
   - ~~Kafka event publisher tests verify anomaly and risk payloads, including
     previous/current risk scores.~~
   - ~~Telemetry and ML failure handling is covered.~~
   - ~~Authorization denies access to another user's device analytics.~~

### High

8. ~~Replace hard-coded metric selection.~~
   - ~~Analytics metrics are now configured through
     `app.analytics.metrics.default-metrics`.~~
   - ~~The default window and response period label are configurable through
     `app.analytics.metrics.default-window` and
     `app.analytics.metrics.period-label`.~~
   - ~~Recalculation iterates all configured metrics and stores per-metric
     baselines and feature windows.~~
   - Note: per-device metric profiles from device-service are still a future
     extension; the hard-coded service-level metric selection is removed.

9. ~~Implement `FeatureSet` creation and `/features` endpoint.~~
   - ~~Recalculation now persists calculated feature windows.~~
   - ~~`GET /analytics/devices/{deviceId}/features` returns recent feature
     sets.~~
   - ~~Feature responses include window start/end, metric name, sample count,
     baseline statistics, and latest value.~~

10. ~~Improve baseline storage model.~~
    - ~~Baseline values are now stored in typed columns for metric, period,
      algorithm version, mean, median, p90, min, max, and sample count.~~
    - ~~`baseline_json` remains for extensible details and backward
      compatibility.~~
    - ~~Baseline responses prefer typed columns and fall back to JSON for older
      rows.~~

11. ~~Preserve anomaly history.~~
    - ~~Recalculation no longer deletes anomaly rows for a device.~~
    - ~~Anomalies now have lifecycle fields `status` and `superseded_at`.~~
    - ~~Previous active anomalies are marked `SUPERSEDED`; new recalculation
      results are saved as `ACTIVE`.~~
    - ~~Read endpoints return active anomalies only.~~

12. ~~Preserve recommendation history or lifecycle.~~
    - ~~Recalculation no longer deletes recommendation rows for a device.~~
    - ~~Recommendations now have lifecycle fields `status` and
      `superseded_at`.~~
    - ~~Previous active recommendations are marked `SUPERSEDED`; new
      recommendations are saved as `ACTIVE`.~~
    - ~~Read endpoints return active recommendations only.~~

13. ~~Add configurable rule engine for anomaly detection.~~
    - ~~Rule thresholds, enablement, severities, scores, and version are now
      configurable under `app.analytics.rules`.~~
    - ~~Rules can be enabled or disabled independently for power spikes,
      consumption growth, and long cycles.~~
    - ~~Anomalies now store `rule_id` and `rule_version` for traceability.~~

14. ~~Split calculation logic into dedicated services.~~
    - ~~Baseline calculation moved to `BaselineCalculator`.~~
    - ~~Risk calculation moved to `RiskScoreCalculator`.~~
    - ~~Anomaly detection moved to `AnomalyDetector`.~~
    - ~~Recommendation generation moved to `RecommendationGenerator`.~~
    - ~~`AnalyticsRecalculationService` now orchestrates persistence,
      downstream calls, and event publication.~~

15. ~~Add idempotency for recalculation and event publication.~~
    - ~~Recalculation tracks `window_start`, `window_end`, and `input_hash` for
      anomalies and risk scores.~~
    - ~~Anomaly and risk ids are stable for the same device/window/input set.~~
    - ~~Outbox event keys deduplicate repeated publication for the same
      calculation input.~~

16. ~~Add transactional outbox for Kafka events.~~
    - ~~Outgoing analytics events are stored in `analytics_event_outbox` inside
      the recalculation transaction.~~
    - ~~A scheduled publisher sends pending outbox rows to Kafka and marks them
      `PUBLISHED` or `FAILED`.~~
    - ~~Outbox publish attempts and last error are retained for retry/debugging.~~

17. ~~Add DLQ and retry handling for analytics consumers.~~
    - ~~Kafka listeners now use a bounded retry error handler.~~
    - ~~Invalid telemetry payloads and events without `deviceId` are published to
      `analytics.telemetry.validated.dlq.v1`.~~
    - ~~DLQ payloads include source topic, failure reason, original payload, and
      failure timestamp.~~

18. ~~Improve risk score model.~~
    - ~~Risk scoring now records explicit factor codes, weights, rule ids,
      sample size, and cap in `risk_scores.explanation_json`.~~
    - ~~Low sample size and missing telemetry are represented as data-quality
      factors.~~
    - ~~The stored model version is `risk-basic-v2`.~~

19. ~~Add multi-tenant data model assumptions.~~
    - ~~Analytics tables now include nullable `account_id` columns and indexes
      for future denormalized tenant filtering and cleanup.~~
    - ~~Authorization still uses device-service as the source of truth until
      upstream events/API responses carry tenant ownership consistently.~~

20. ~~Add retention policy for analytics data.~~
    - ~~Retention durations are configurable under `app.analytics.retention`.~~
    - ~~A scheduled cleanup job removes old baselines, feature sets,
      predictions, anomalies, risk scores, jobs, recommendations, and completed
      outbox rows.~~
    - ~~The cleanup can be disabled with `app.analytics.retention.enabled=false`.~~

### Medium

21. ~~Use a dedicated analytics database/schema.~~
    - ~~Local analytics config now uses `ANALYTICS_POSTGRES_DB`, defaulting to
      `voltcanary_analytics`, instead of the shared `POSTGRES_DB=voltcanary`.~~
    - ~~Docker Compose and Kubernetes init defaults create
      `voltcanary_analytics`.~~
    - ~~Analytics migrations own tables inside that dedicated database.~~

22. ~~Remove unused WebFlux dependency or switch to reactive client intentionally.~~
    - ~~Current code uses synchronous `RestClient`, not `WebClient`.~~
    - ~~Removed `spring-boot-starter-webflux` from analytics-service.~~

23. ~~Add service-to-service authentication for telemetry and ML calls.~~
    - ~~Telemetry and ML REST calls now send `X-Internal-Service-Token`.~~
    - ~~The source service is identified with `X-Source-Service`.~~
    - ~~Credentials are configured through `app.internal.service-token` and
      `app.internal.service-name`.~~

24. ~~Add observability metrics.~~
    - ~~Recalculation jobs are counted by enqueue/completion status.~~
    - ~~Recalculation and job durations are timed.~~
    - ~~Telemetry fetch failures are counted.~~
    - ~~ML inference failures and latency are tracked.~~
    - ~~Anomalies are counted by type and severity.~~
    - ~~Outgoing analytics events are counted on enqueue and publish.~~

25. ~~Add structured logs.~~
    - ~~Job logs include device id and job id, with MDC context during job
      execution.~~
    - ~~Recalculation completion logs include device id, calculation window,
      input hash, metric count, anomaly count, and risk details.~~
    - ~~Failure logs use reason codes and avoid full telemetry payloads.~~

26. ~~Add OpenAPI documentation.~~
    - ~~Analytics service now includes springdoc OpenAPI support.~~
    - ~~Analytics endpoints are tagged and documented with operation
      descriptions.~~
    - ~~Bearer and internal service token security schemes are declared.~~

27. ~~Improve prediction result model.~~
    - ~~Prediction results now have typed columns for model version, anomaly
      score, degradation score, confidence, risk level, and status.~~
    - ~~Responses prefer typed columns and fall back to `prediction_json` for
      older rows.~~
    - ~~Explanation lists remain in structured JSON for forward-compatible
      versioning.~~

28. ~~Add performance tests.~~
    - ~~Recalculation is tested with small, medium, and large telemetry
      windows.~~
    - ~~Telemetry-service query cost is verified as one configured metric fetch
      per recalculation.~~
    - ~~Database write volume is bounded for baseline, feature, anomaly, risk,
      recommendation, prediction, and outbox rows.~~
    - ~~Outbox event creation is verified under large telemetry input.~~

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
