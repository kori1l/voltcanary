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

23. ~~Add user deletion and downstream lifecycle propagation.~~
    - ~~Added soft-delete through `DELETE /auth/admin/users/{userId}` with
      `DELETED` status.~~
    - ~~Admin status changes and deletion revoke active refresh sessions when the
      target user becomes inactive.~~
    - ~~Identity synchronously syncs user lifecycle to device-service account
      lifecycle when enabled.~~
    - ~~Existing access tokens remain cryptographically valid until expiry, but
      `/accounts/current` and device-domain access are blocked by account
      lifecycle.~~
    - ~~Device-service internal data-source existence checks require the owning
      account to be `ACTIVE`, so old source tokens cannot ingest after user
      deletion or disable.~~
    - ~~Added identity/device/ingestion regression tests and live gateway-level
      destructive smoke coverage for delete/deactivate behavior.~~
    - Follow-up: decide support/admin inspectability for deleted accounts in the
      product UI if needed.

24. ~~Improve API error consistency.~~
    - ~~Keep login failure responses generic.~~
    - ~~Ensure internal details do not leak through public error responses.~~
    - ~~Keep detailed failure reasons only in audit logs.~~

25. ~~Document the auth contract.~~
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

17. ~~Define account-deleted physical cascade behavior for devices and sources.~~
    - ~~`POST /internal/accounts/users/{userId}/lifecycle` with `DELETED` marks
      the device account deleted, blocks device API access, and internal source
      existence returns `false`.~~
    - ~~Account deletion uses a soft cleanup policy rather than hard-deleting
      registry rows: owned devices are moved to `DECOMMISSIONED`, owned data
      sources are disabled and moved to `REVOKED`, and active source tokens get
      `token_revoked_at`.~~
    - ~~Locations, sensors, and analytics configs are retained as historical
      account-owned records through their existing device/location references;
      the decommissioned device state prevents further normal use.~~
    - ~~Registry-change events are emitted for cleanup transitions when device
      registry publishing is enabled.~~
    - ~~Telemetry history retention remains a separate product/data-retention
      policy, but deleted accounts cannot read device API or ingest through old
      credentials.~~

18. ~~Add audit logging.~~
    - ~~Added structured audit logging for device, location, source, sensor,
      and analytics-config mutations.~~
    - ~~Audit logs include user id, account id, correlation id, target id, and
      action type.~~
    - ~~Source tokens and other secrets are not logged.~~
    - ~~Device tests verify representative audit calls.~~

19. ~~Add OpenAPI documentation for device-service.~~
    - ~~Added springdoc OpenAPI support with `/v3/api-docs` and
      `/swagger-ui.html`.~~
    - ~~Configured bearer auth and `X-Internal-Service-Token` security schemes.~~
    - ~~Updated `docs/api/device-api.md` with account, location, device, data
      source, sensor, analytics config, auth, internal, error, audit, and
      OpenAPI notes.~~

### Medium

20. ~~Consider using gateway-provided user headers.~~
    - ~~Controllers now receive request headers and resolve the current user via
      `AuthenticatedUserResolver`.~~
    - ~~Gateway-provided `X-User-Id`, `X-User-Email`, and `X-User-Role` are the
      primary downstream auth contract.~~
    - ~~Direct-service local development behavior is documented in
      `docs/api/device-api.md`.~~

21. ~~Prepare for asymmetric JWT verification if services keep validating
    tokens.~~
    - ~~Direct bearer-token fallback in `device-service` uses shared
      `common-security` public-key JWT validation.~~
    - ~~The validator supports `kid`, configured public keys, issuer, audience,
      token type, and standard claim checks aligned with gateway.~~
    - ~~The primary production contract remains gateway-provided user headers.~~

22. ~~Add device metadata support.~~
    - ~~Added `devices.metadata_json` and optional `metadata` on device
      create/update responses.~~
    - ~~Common indexed fields remain real columns (`manufacturer`, `model`,
      status, type, location).~~
    - ~~Metadata rejects obvious secret keys such as `password`, `token`,
      `secret`, and `credential`.~~
    - ~~Device tests cover metadata persistence and secret rejection.~~

23. ~~Add location hierarchy or richer location model if needed.~~
    - ~~Locations now support `parentId`, `building`, `floor`, `room`, `zone`,
      and `tags`.~~
    - ~~Parent locations are ownership-checked and self-parenting is rejected.~~
    - ~~Location search includes the richer hierarchy fields.~~

24. ~~Add import/export support for device registry.~~
    - ~~Added CSV device import through `POST /devices/imports/csv`.~~
    - ~~Added inventory export through `GET /devices/export.csv`.~~
    - ~~Added CSV data-source import/export under
      `/devices/{deviceId}/data-sources/imports/csv` and
      `/devices/{deviceId}/data-sources/export.csv`.~~
    - ~~Imports validate all rows before creating registry resources.~~

25. ~~Add operational metrics.~~
    - ~~Count devices by status and seeded type.~~
    - ~~Count data sources by protocol and status.~~
    - ~~Track internal validation request latency.~~
    - ~~Track failed ownership checks and rejected operations.~~

26. ~~Clean up unused scaffolding packages.~~
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

1. ~~Add ownership and authorization checks for alert APIs.~~
   - ~~`GET /alerts` now filters returned alerts through `device-service`
     ownership checks for the current gateway-provided user context.~~
   - ~~`GET /alerts/{id}`, `POST /alerts/{id}/ack`, and
     `POST /alerts/{id}/resolve` enforce the same device ownership check.~~
   - ~~`ADMIN` and `SUPPORT` roles can read across devices.~~

2. ~~Add account or tenant reference to alerts.~~
   - ~~Added nullable `alerts.account_id` and account-oriented indexes as the
     storage base for tenant-local enforcement.~~
   - ~~Analytics events currently carry `device_id`, so alert-service resolves
     `account_id` through the trusted device-service owner lookup during alert
     creation.~~
   - ~~Added device-service internal owner endpoint
     `GET /internal/devices/{deviceId}/owner`.~~
   - ~~Index `(account_id, created_at)` and
     `(account_id, status, created_at)`.~~

3. ~~Add unique constraint for deduplication.~~
   - ~~`deduplication_key` now has a database-level unique index.~~
   - ~~Repeated events continue to return the existing alert.~~

4. ~~Implement retry and DLQ handling for Kafka consumers.~~
   - ~~Bad or repeatedly failing events no longer poison consumer processing.~~
   - ~~Added bounded retry policy for alert Kafka listeners.~~
   - ~~Added DLQ topics for `anomaly.detected.v1` and
     `risk-score.changed.v1`.~~
   - ~~DLQ records include Spring Kafka failure/source headers and preserve the
     original event value when the event was successfully deserialized.~~

5. ~~Add tests for alert authorization and tenant isolation.~~
   - ~~User sees only alerts for devices they can access.~~
   - ~~User cannot get, acknowledge, or resolve another user's alert.~~
   - Admin/support behavior is explicit if such roles are added.
   - ~~Alert list filtering does not leak foreign device ids.~~

6. ~~Add tests for deduplication under repeated events.~~
   - ~~Same anomaly event creates only one alert.~~
   - ~~Same deduplication key under concurrent handling creates only one
     alert.~~
   - ~~Different hours or different severities create separate alerts by
     policy.~~
   - ~~Resolved alert behavior on repeated events is defined and tested.~~
   - ~~Alert creation uses a per-deduplication-key in-process lock plus the
     database unique index as a second line of protection.~~

### High

7. ~~Add filtering and pagination to alert list APIs.~~
   - ~~Support filters by `deviceId`, `status`, `severity`, `alertType`,
     `from`, and `to`.~~
   - ~~Added page-based pagination through `GET /alerts/page`.~~
   - ~~Keep default ordering by newest first.~~
   - ~~Avoid returning only a hard-coded global top 100 by supporting a bounded
     `limit` parameter.~~
   - ~~Pagination metadata is computed after ownership filtering so tenant
     isolation remains accurate.~~

8. ~~Implement notification delivery.~~
   - ~~Added after-commit asynchronous delivery dispatch so Kafka consumers do
     not wait on external providers.~~
   - ~~Implemented `WEBHOOK` delivery worker with retry, timeout, sent/failed
     attempt updates, and delivery metrics.~~
   - ~~Added HTTP-backed delivery workers for `EMAIL`, `TELEGRAM`, `SLACK`,
     `SMS`, and `PUSH` channels in addition to `WEBHOOK`.~~
   - ~~Store delivery attempts and outcomes.~~
   - ~~Avoid blocking Kafka consumers on slow external notification providers.~~

9. ~~Add notification channel API.~~
   - ~~Added create, list, update, disable, enable, and delete endpoints under
     `/alerts/notification-channels`.~~
   - ~~Channels are scoped by account.~~
   - ~~Channel configuration is validated.~~
   - ~~Secrets are referenced through `credentialsRef`; inline secret-like
     config keys are rejected.~~

10. ~~Add notification delivery status model.~~
    - ~~Store notification attempts separately from alerts.~~
    - ~~Track `PENDING`, `SENT`, `FAILED`, `RETRYING`, and `DISABLED`.~~
    - ~~Store retry count, last error code, provider response summary, and sent
      time.~~
    - ~~Expose per-alert attempts through
      `GET /alerts/{id}/notification-attempts`.~~

11. ~~Add escalation policy.~~
    - ~~Unacknowledged `OPEN` `WARNING`, `HIGH`, and `CRITICAL` alerts are
      escalated after configurable thresholds.~~
    - ~~Escalated alerts remain `OPEN`; the escalation is stored as an
      `ESCALATED` event in `alert_events`.~~
    - ~~Escalation triggers another notification delivery pass with
      `ALERT_ESCALATED` webhook payloads.~~
    - ~~Escalation scans are scheduled and idempotent per alert.~~

12. ~~Improve deduplication strategy.~~
    - ~~Current key is `deviceId:alertType:severity:hour`.~~
    - ~~Policy is source-event idempotency first, then active condition window
      deduplication by `deviceId + alertType + severity + hour`.~~
    - ~~Rule/window/input hash, anomaly id, and risk score id are stored as
      correlation context but do not currently split deduplication keys.~~
    - ~~A new event for an already resolved matching alert creates a new
      `OPEN` alert; repeated events then deduplicate into that unresolved
      alert.~~
    - ~~Moved key construction and existing-alert lookup into
      `AlertDeduplicationService`.~~

13. ~~Add alert lifecycle actor fields.~~
    - ~~Added `acknowledged_by`.~~
    - ~~Added `resolved_by`.~~
    - ~~Added `assigned_to` for future manual assignment flows.~~
    - ~~`alert_events` now stores `actor_id`.~~

14. ~~Standardize alert event payloads.~~
    - ~~`CREATED`, `ACKNOWLEDGED`, and `RESOLVED` events now use a consistent
      payload shape.~~
    - ~~Payloads include event id, alert id, actor id, action, timestamp,
      status, reason, and context.~~

15. ~~Align severity taxonomy across services.~~
    - ~~Analytics uses levels such as `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.~~
    - ~~Alert service now supports `INFO`, `WARNING`, `HIGH`, and
      `CRITICAL`.~~
    - ~~Mapping is `LOW -> INFO`, `MEDIUM -> WARNING`, `HIGH -> HIGH`, and
      `CRITICAL -> CRITICAL`.~~
    - ~~Frontend display already handles `HIGH`; API documentation now includes
      the mapping.~~

16. ~~Add alert source and correlation fields.~~
    - ~~Store source event id.~~
    - ~~Store anomaly id when available.~~
    - ~~Store risk score id when available.~~
    - ~~Store rule id or analytics calculation id when available.~~
    - ~~Alerts now keep analytics rule/window/input hash context in DB, API
      responses, alert event payloads, and webhook payloads.~~

17. ~~Add idempotency by source event id.~~
    - ~~Alerts now store source event references.~~
    - ~~Repeated analytics events with the same source event id return the
      existing alert instead of creating another one.~~

18. ~~Add alert reopen policy.~~
    - ~~When the same condition appears again after an alert was resolved, the
      service creates a new `OPEN` alert.~~
    - ~~The old resolved alert remains closed for audit/history.~~
    - ~~Repeated events while the new alert is unresolved deduplicate into the
      new alert.~~
    - ~~Behavior is covered by service/API tests.~~

19. ~~Add notification preferences.~~
    - ~~Added account-level default notification preferences under
      `/alerts/notification-preferences`.~~
    - ~~Preferences configure severities, quiet hours, channel types, and
      device filters.~~
    - ~~Critical alerts can bypass quiet hours when configured.~~

20. ~~Add alert aggregation for dashboards.~~
    - ~~Count open, acknowledged, and resolved alerts.~~
    - ~~Count by severity and device.~~
    - ~~Added `GET /alerts/summary` so the frontend does not need to compute
      dashboard counts from raw alerts.~~

### Medium

21. ~~Add service-to-service trust boundary documentation.~~
    - ~~Alert service trusts events from analytics topics.~~
    - ~~Documented allowed producers for `anomaly.detected.v1` and
      `risk-score.changed.v1`.~~
    - ~~Documented Kafka ACL and deployment-level restrictions for
      non-local environments in `docs/alert-service-trust-boundary.md`.~~

22. ~~Add notification channel secrets handling.~~
    - ~~`notification_channels.config_json` stores only non-secret config.~~
    - ~~Added `notification_channels.credentials_ref`.~~
    - ~~Actual tokens/passwords/webhook secrets must live in Vault or another
      secret manager.~~
    - ~~API validation rejects secret-like keys inside channel config.~~

23. ~~Add retention policy.~~
    - ~~Resolved alerts default to 180 days.~~
    - ~~Alert events default to 365 days.~~
    - ~~Notification attempts default to 90 days.~~
    - ~~Added scheduled cleanup job with configurable TTLs.~~

24. ~~Add OpenAPI documentation.~~
    - ~~Document alert list filters and page response metadata.~~
    - ~~Document lifecycle actions.~~
    - ~~Document error responses.~~
    - ~~Document notification channel endpoints.~~
    - ~~Document notification delivery attempt statuses and endpoint.~~

25. ~~Add observability metrics.~~
    - ~~Count alerts created by type and severity.~~
    - ~~Count acknowledgements and resolutions.~~
    - ~~Track time-to-ack and time-to-resolve.~~
    - ~~Count deduplicated alert events by reason.~~
    - ~~Count Kafka consumer failures.~~
    - ~~Count notification attempts, successes, failures, and retries.~~

26. ~~Add structured logs.~~
    - ~~Alert lifecycle actions include correlation id, alert id, device id,
      source event id, actor id, and action.~~
    - ~~Alert creation and deduplication logs include alert id, device id,
      source event id, alert type, severity, and reason.~~
    - ~~Notification secrets are not logged because notification delivery is not
      implemented yet.~~
    - ~~Deduplication decisions log clear reason codes.~~

27. ~~Add admin/support workflows if needed.~~
    - ~~Added `/alerts/support/search` for support/admin users to search across
      accounts.~~
    - ~~Added explicit role-based access check for support search.~~
    - ~~Regular users remain scoped through existing ownership checks.~~

28. ~~Clean up unused scaffolding.~~
    - ~~`AlertDeduplicationService` is now wired into alert creation logic.~~
    - ~~Empty placeholder packages were removed from the alert-service Java
      package tree.~~

## Horizontal Scaling And Eventual Consistency

The current asynchronous path is `ingestion-service -> telemetry-service ->
analytics-service -> alert-service`. It is suitable for MVP and local
single-replica development, but it is not yet a complete horizontally scalable
eventually consistent system. The outbox pattern is currently used only in
`analytics-service` through `analytics_event_outbox`. Other services still rely
on direct Kafka sends, in-process schedulers, or in-memory state.

### Critical

1. ~~Standardize durable event publication with transactional outbox.~~
   - ~~Current issue: `analytics-service` has an outbox, but
     `telemetry-service` publishes `telemetry.validated.v1` and telemetry DLQ
     events directly from the telemetry processing transaction.~~
   - ~~`telemetry-service` now stores `telemetry.validated.v1` and telemetry
     DLQ events in `telemetry_event_outbox` inside the telemetry processing
     transaction; a scheduled outbox publisher sends JSON payloads to Kafka.~~
   - ~~Current issue: `device-service` publishes `device-registry.changed.v1`
     directly after device/source database writes.~~
   - ~~`device-service` now stores `device-registry.changed.v1` events in
     `device_event_outbox` inside the same transaction as domain writes, and a
     scheduled publisher sends JSON payloads to Kafka.~~
   - ~~`identity-service` stores `identity.account-lifecycle.v1` events in
     `identity_event_outbox` inside the same transaction as user status changes
     and delete transitions.~~
   - ~~`device-service` consumes `identity.account-lifecycle.v1` with a
     `processed_events` inbox table before applying account lifecycle state.~~
   - ~~Add a common outbox table shape per state-owning service: `id`,
     `event_key`, `topic`, `message_key`, `payload_json`, `status`,
     `attempts`, `next_attempt_at`, `locked_at`, `locked_by`, `last_error`,
     `created_at`, and `published_at`.~~
   - ~~Write outbox rows in the same database transaction as the domain state
     change.~~
   - ~~Publish from outbox workers, not directly from domain services, for
     events that are required to keep downstream projections eventually
     consistent.~~
   - ~~Keep direct synchronous calls only for hard fail-closed security
     decisions, and pair them with a durable retry/outbox record for
     compensation.~~

2. ~~Make outbox publishers safe with multiple replicas.~~
   - ~~Current issue: `analytics-service` scheduled publisher reads
     `findTop100ByStatusOrderByCreatedAtAsc("PENDING")` in every pod without
     row claiming, leasing, `SKIP LOCKED`, or a publisher id.~~
   - ~~`analytics-service` outbox rows now have `next_attempt_at`, `locked_at`,
     and `locked_by`.~~
   - ~~`analytics-service` and `telemetry-service` outbox publishers atomically
     claim due rows, move them to `PROCESSING`, publish outside the claim
     transaction, and then mark each row `PUBLISHED`, retryable `PENDING`, or
     `FAILED`.~~
   - ~~`device-service` uses the same claim/lease/retry publisher model for
     `device_event_outbox`.~~
   - ~~Expired `PROCESSING` rows are claimable again after the lease TTL.~~
   - ~~Preserve consumer idempotency because duplicates are still possible after
     Kafka accepts a message but before the outbox row is marked `PUBLISHED`.~~

3. ~~Add consumer inbox or processed-event idempotency tables.~~
   - ~~Current issue: Kafka delivery should be treated as at-least-once, but
     not every consumer stores `eventId` processing state.~~
   - `telemetry-service` deduplicates metric rows with a unique measurement
     index, but ~~`telemetry_readings` can still be inserted again on replay.~~
   - ~~`telemetry-service` now records consumed `telemetry.raw.v1` event ids in
     `processed_events` inside the same transaction as telemetry rows and
     telemetry outbox side effects; duplicate replays skip processing before
     inserting another reading.~~
   - ~~`analytics-service` deduplicates active jobs by lookup, but concurrent
     pods can race before a job is persisted.~~
   - ~~`analytics-service` records consumed `telemetry.validated.v1` event ids
     in `processed_events` before enqueueing recalculation jobs; duplicate
     replays skip job side effects.~~
   - ~~`alert-service` has unique alert constraints for source event and
     deduplication key, but scheduled escalation and notification attempts can
     still duplicate side effects.~~
   - ~~`alert-service` records consumed `anomaly.detected.v1` and
     `risk-score.changed.v1` event ids in `processed_events` before alert side
     effects; duplicate replays skip handler side effects.~~
   - ~~Add a `processed_events` or service-specific inbox table keyed by source
     topic, partition, offset, and/or event id where side effects matter.~~
   - ~~Commit Kafka offsets only after the database transaction that records the
     inbox row and side effects succeeds.~~

4. ~~Move multi-replica control state out of process memory.~~
   - ~~Current issue: gateway rate limits use `ConcurrentHashMap`, so every
     `api-gateway` pod enforces an independent limit.~~
   - ~~Current issue: ingestion rate limits use `ConcurrentHashMap`, so every
     `ingestion-service` pod enforces independent source/device/account limits.~~
   - ~~Current issue: `ingestion-service` defaults to in-memory idempotency in
     `application.yml`; only the `local` profile enables Redis idempotency.~~
   - ~~Current issue: telemetry processing rate limits use `ConcurrentHashMap`,
     so every `telemetry-service` pod has an independent per-device limiter.~~
   - ~~`ingestion-service` default configuration now enables Redis-backed
     idempotency through `INGESTION_IDEMPOTENCY_REDIS_ENABLED`, with Redis
     host/port configured from environment variables.~~
   - ~~Docker Compose and Kubernetes base config set
     `INGESTION_IDEMPOTENCY_REDIS_ENABLED=true`.~~
   - ~~`telemetry-service` per-device processing rate limits now use Redis
     atomic counters when `TELEMETRY_RATE_LIMIT_REDIS_ENABLED=true`; Compose and
     Kubernetes base config enable this by default, while tests can explicitly
     disable it.~~
   - ~~`ingestion-service` source/device/account/ip rate limits now use Redis
     atomic counters when `INGESTION_RATE_LIMIT_REDIS_ENABLED=true`; Compose and
     Kubernetes base config enable this by default, while tests can explicitly
     disable it.~~
   - ~~`api-gateway` route-category/client-IP rate limits now use Redis atomic
     counters when `GATEWAY_RATE_LIMIT_REDIS_ENABLED=true`; Compose and
     Kubernetes base config enable this by default, while tests can explicitly
     disable it.~~
   - ~~Move gateway, ingestion, and telemetry rate limits to Redis or another
     shared atomic store.~~
   - ~~Keep local in-memory implementations only behind explicit local/test
     profiles.~~

5. ~~Add distributed locks or claim-based work for scheduled jobs.~~
   - ~~Current issue: `@Scheduled` jobs run in every pod for identity
     refresh-token cleanup, analytics outbox publishing, analytics retention,
     alert escalation, and alert retention.~~
   - ~~Idempotent cleanup jobs may tolerate duplicate runs, but they still
     create unnecessary load under horizontal scaling.~~
   - ~~Alert escalation can produce duplicate escalation events or duplicate
     notifications because `existsByAlertIdAndEventType` is not an atomic
     distributed claim.~~
   - ~~Use ShedLock, PostgreSQL advisory locks, leader election, or claimable DB
     rows for scheduled work.~~
   - ~~Add database uniqueness for one-time side effects, for example one
     escalation event per alert unless policy explicitly allows repeated
     escalation levels.~~
   - ~~`identity-service`, `analytics-service`, and `alert-service` now use a
     `scheduler_locks` lease table for singleton scheduled work.~~
   - ~~Identity refresh-token cleanup, analytics retention cleanup, alert
     escalation scan, and alert retention cleanup acquire a database lease
     before running.~~
   - ~~Outbox publishers use claim-based row leasing, so multiple replicas can
     publish without processing the same outbox row concurrently.~~
   - ~~Alert escalation uses `alert_events.deduplication_key` with a unique
     index and dispatches notifications only after the escalation event insert
     succeeds.~~

6. ~~Make simulator runs single-owner or distributed.~~
   - ~~Current issue: `simulator-service` tracks active runs in
     `RunningSimulationRegistry`, an in-memory map of `deviceId` to
     `ScheduledFuture`.~~
   - ~~Multiple simulator pods can start the same device simulation because the
     registry is local to each pod.~~
   - ~~A stopped or restarted pod marks previous runs stopped on startup, but it
     does not transfer ownership to another pod.~~
   - ~~Either document `simulator-service` as single-replica only, or replace
     the in-memory registry with database leases/workers keyed by device id.~~
   - ~~`simulation_runs.active_key` plus a unique `(device_id, active_key)`
     index prevents more than one active run per device across simulator
     replicas.~~
   - ~~`lease_owner` and `lease_until` identify the owning replica and allow
     expired active runs to be stopped during startup or scheduled
     reconciliation.~~
   - ~~Remote stop requests move the run to `STOPPING` without releasing the
     active claim; the owner or stale-lease reconciliation clears the claim when
     the run is actually stopped.~~

7. ~~Define Kafka producer and topic scaling policy.~~
   - ~~Kafka producer settings are explicit for current event producers:
     `acks=all`, `enable.idempotence=true`, max retries,
     `max.in.flight.requests.per.connection=5`, delivery timeout, and retry
     backoff.~~
   - ~~Keep stable message keys: device-scoped telemetry and analytics events
     should use `deviceId`; account-scoped registry events should use
     `accountId` only when per-account ordering is intended.~~
   - ~~Set topic partition counts based on expected max consumer replicas and
     ordering boundaries.~~
   - ~~Document listener concurrency per service and keep it <= partition
     count.~~
   - ~~`docs/architecture/04-event-driven-architecture.md` now defines topic
     keys, ordering boundaries, default consumer groups, listener concurrency,
     and partition-count guidance.~~

8. ~~Make identity lifecycle propagation durable and replayable.~~
   - ~~Current lifecycle sync to device-service is synchronous and optional
     behind `IDENTITY_DEVICE_LIFECYCLE_SYNC_ENABLED`.~~
   - ~~User deletion/disable is a security-sensitive state transition, so the
     system must choose either fail-closed synchronous propagation or durable
     asynchronous propagation with immediate token/session revocation and
     downstream retry.~~
   - ~~Add an identity lifecycle outbox event and a device-service inbox
     handler, or keep the synchronous command but persist a retryable sync task
     in the identity transaction.~~
   - ~~Identity status/delete transactions revoke refresh sessions immediately
     and enqueue durable `identity.account-lifecycle.v1` events.~~
   - ~~Identity outbox publication is lease/retry based and `device-service`
     applies events idempotently via `processed_events`.~~
   - ~~Identity lifecycle reconciliation compares recent identity user statuses
     with device-service account lifecycle status under a distributed scheduler
     lock and re-enqueues lifecycle outbox events for missing or drifted
     accounts.~~

### High

9. ~~Make analytics job execution claim-based.~~
   - ~~Current issue: `AnalyticsJobService` checks for active jobs and then
     inserts a new one without a database constraint that prevents concurrent
     active jobs for the same device.~~
   - ~~Add a unique partial index or deterministic job key for active
     recalculation jobs, for example `(device_id, job_type)` where status is
     `PENDING` or `RUNNING`.~~
   - ~~`analytics_jobs.active_key` plus unique
     `(device_id, job_type, active_key)` prevents more than one active
     recalculation job per device and job type; completed/failed jobs clear the
     key.~~
   - ~~Add worker leasing to claim `PENDING` jobs instead of immediately
     running `@Async` in the pod that created the job.~~
   - ~~Add recovery for stale `RUNNING` jobs after worker crashes.~~
   - ~~Async recalculation enqueue now leaves jobs `PENDING`; scheduled
     analytics workers claim jobs with `locked_at`/`locked_by`, move them to
     `RUNNING`, and clear active/lock state on completion or failure.~~
   - ~~Stale `RUNNING` jobs with expired locks are claimable again by another
     worker.~~
   - ~~Derive recalculation windows from event time or fixed buckets when
     idempotency must be deterministic; current `now`-based windows can create
     different input hashes for repeated triggers.~~

10. ~~Make alert notification delivery durable and idempotent.~~
    - ~~Notification delivery no longer depends on creating attempts inside an
      after-commit async task. Alert creation/escalation now creates durable
      `notification_attempts` rows before commit.~~
    - ~~`notification_attempts` are the durable delivery queue. Attempts include
      `delivery_event_type`, `idempotency_key`, `next_attempt_at`, `locked_at`,
      and `locked_by`.~~
    - ~~Workers claim due attempts with row leases and move them through
      `PROCESSING`, `SENT`, `RETRYING`, `FAILED`, or `DISABLED`. Stale
      `PROCESSING` attempts become claimable after the lease expires.~~
    - ~~Notification side effects use stable idempotency keys:
      `alert_id + channel_id + delivery_event_type`. Duplicate dispatches reuse
      the existing attempt instead of creating another delivery.~~
    - ~~HTTP provider calls include the idempotency key in the JSON payload and
      the `Idempotency-Key` header.~~

11. ~~Add service-level reconciliation loops.~~
    - ~~Add telemetry-to-analytics reconciliation for persisted telemetry rows
      that did not produce `telemetry.validated.v1`.~~
      - ~~`telemetry-service` now tags validated outbox rows with
        `reading_id`, uses that as the idempotency key for realtime processing,
        and periodically scans persisted readings with measurements but no
        corresponding `telemetry.validated.v1` outbox row.~~
      - ~~Backfill sources remain excluded from realtime validated-event
        recovery, matching the normal processing path.~~
    - ~~Add analytics outbox reconciliation for stuck or failed outbox rows.~~
      - ~~`analytics-service` now periodically claims a scheduler lock and
        resets old `FAILED` and stale `PROCESSING` `analytics_event_outbox` rows
        back to `PENDING` with delayed retry metadata.~~
      - ~~The reconciliation loop is configurable through
        `app.analytics.outbox.reconciliation.*` and is covered by a focused
        Flyway-backed test.~~
    - ~~Add alert reconciliation for analytics events that were processed but did
      not create the expected alert due to transient downstream failures.~~
      - ~~`alert-service` now stores consumed analytics events in
        `alert_analytics_event_inbox`, records processing status and retry
        metadata, and replays `FAILED` or stale `PROCESSING` entries under a
        scheduler lock.~~
      - ~~Replay is idempotent because alert creation is still keyed by source
        event/deduplication data, and terminal inbox entries are skipped.~~
    - ~~Add device registry reconciliation so ingestion caches and projections
      can be rebuilt from device-service if registry events are missed.~~
      - ~~`ingestion-service` now periodically reconciles its local
        `SourceRegistryCache` against device-service, refreshing cached source
        metadata by `dataSourceId` and evicting sources that no longer exist.~~
      - ~~The loop is per-pod because the cache is in-memory, configurable
        through `app.ingestion.source-registry-reconciliation.*`, and covered by
        a focused unit test.~~

12. ~~Standardize event envelopes across all asynchronous topics.~~
    - ~~`docs/events/event-envelope.md` defines event id, producer,
      correlation, versioning, account context, and transition expectations for
      explicit `EventEnvelope<T>` values and direct common event records.~~
    - ~~Common published event records now expose `eventId`, `eventVersion`,
      `correlationId`, `producer`, `accountId`, and `occurredAt` metadata fields
      while keeping backward-compatible constructors for existing call sites.~~
    - ~~The main producer paths in ingestion, telemetry, analytics, device, and
      identity populate producer/version metadata and propagate correlation or
      account context where it is available.~~
    - ~~`EventContractTest` verifies envelope JSON and metadata fields across
      all common asynchronous event records.~~

13. ~~Add a horizontal scaling matrix.~~
    - ~~Added `docs/architecture/09-horizontal-scaling-matrix.md`.~~
    - ~~The matrix marks `api-gateway`, `ingestion-service`,
      `telemetry-service`, `analytics-service`, `alert-service`, and
      `simulator-service` separately.~~
    - ~~It lists required external state such as Redis, Kafka partitions,
      database locks, outbox/inbox tables, and process-local state.~~
    - ~~It calls out services that should stay single-replica until
      process-local ownership or in-memory protection is replaced by shared
      infrastructure.~~

14. ~~Add failover and replay tests.~~
    - ~~Duplicate Kafka delivery is covered for telemetry, analytics, and alert
      consumers: telemetry raw events are idempotent through
      `TelemetryPipelineTest`, analytics validated events are idempotent through
      `AnalyticsConsumerReplayTest`, and alert anomaly events are idempotent
      through `AlertServiceApiTest`.~~
    - ~~Producer crash after database commit and before Kafka publish is covered
      by pending-row replay tests in `TelemetryOutboxReplayTest` and
      `AnalyticsOutboxReplayTest`.~~
    - ~~Outbox worker crash after Kafka ack and before marking `PUBLISHED` is
      covered by stale `PROCESSING` replay tests in `TelemetryOutboxReplayTest`
      and `AnalyticsOutboxReplayTest`.~~
    - ~~Two-replica claiming behavior is covered for analytics jobs by
      `AnalyticsJobClaimReplayTest`, alert notification delivery by
      `NotificationChannelApiTest`, and alert escalation/idempotent alert
      creation by existing alert service tests.~~
    - ~~Redis outage behavior is covered for ingestion rate limiting fallback
      and Redis idempotency fail-closed behavior by `IngestionRedisOutageTest`
      and `RedisIdempotencyServiceOutageTest`.~~

### Medium

15. ~~Add operational metrics for eventual consistency.~~
    - ~~Telemetry, analytics, and alert services expose SQL-backed gauges for
      outbox depth, oldest pending age, failed rows, retry count, and stuck
      `PROCESSING` leases.~~
    - ~~Alert-service exposes analytics inbox depth and stuck processing gauges;
      analytics-service exposes job depth and stale running job gauges.~~
    - ~~Scheduler lock acquisition/skipped runs are counted in identity,
      analytics, and alert scheduler lock services.~~
    - ~~Ingestion records Redis-backed rate-limit and idempotency latency and
      error metrics.~~
    - ~~`docs/architecture/10-consistency-contracts.md` lists alerting signals
      for growing outbox lag, failed rows, lock contention, Redis errors, and
      stale running jobs.~~

16. ~~Document consistency contracts per user-visible flow.~~
    - ~~Added `docs/architecture/10-consistency-contracts.md`.~~
    - ~~The document defines which operations are strongly consistent in the
      request path and which propagate through Kafka/outbox.~~
    - ~~It defines operating propagation targets for telemetry appearing in
      dashboards, analytics results, alert creation, and notifications.~~
    - ~~It defines client retry expectations for ingestion `202`, `409`, `429`,
      `503`, and ambiguous network timeouts.~~
    - ~~It documents replay procedures for outbox rows, inbox rows, DLQ records,
      and failed/stale analytics jobs.~~

## Project Structure

The current repository layout works for MVP development, but the package and
folder structure should be normalized before the system grows further. The goal
is to make services easier to navigate, not to introduce a deeper architecture.
Prefer a small layered package layout per service unless a service has a
documented reason to use feature slices.

### Critical

1. ~~Standardize Java package layout inside every service.~~
   - ~~Use the same top-level packages where they apply:~~
     - `controller`
     - `service`
     - `repository`
     - `domain`
     - `dto`
     - `mapper`
     - `config`
     - `security`
     - `exception`
   - ~~All REST controllers now live in `controller`, including internal
     controllers.~~
   - ~~JPA entities and domain aggregate models now live in `domain`.~~
   - ~~Repositories now live in `repository`; feature-package repositories were
     moved.~~
   - ~~Request/response records live in `dto` where the service uses DTOs.~~
   - ~~Integration clients remain in service-specific infrastructure packages
     only where those packages are already meaningful.~~
   - ~~`domain` is used consistently instead of introducing a second `entity`
     synonym.~~

2. ~~Flatten feature-sliced packages where they currently obscure common roles.~~
   - ~~Current examples were `device/device`, `device/datasource`,
     `device/location`, `device/sensor`, and analytics packages such as
     `anomaly`, `baseline`, `prediction`, `risk`, and `recommendation`.~~
   - ~~Controllers were moved from feature packages into `controller`.~~
   - ~~JPA entities were moved from feature packages into `domain`.~~
   - ~~Repositories were moved from feature packages into `repository`.~~
   - ~~Small device feature packages were flattened into standard layer
     packages.~~
   - ~~Analytics keeps calculation packages such as `anomaly` and `risk` for
     cohesive non-JPA algorithms; entity classes are no longer in those
     packages.~~

3. ~~Eliminate Spring field injection and method injection in production code.~~
   - ~~Production code now uses constructor injection for required
     dependencies.~~
   - ~~`src/main/java` has no `@Autowired` usages.~~
   - ~~Single-constructor services no longer carry redundant constructor
     `@Autowired`.~~
   - ~~No method injection remains in production code.~~
   - ~~Tests may still use field injection where Spring test setup is clearer.~~

4. ~~Move `@Value` configuration access into typed configuration classes.~~
   - ~~Scattered `@Value` constructor parameters were replaced with
     typed `@ConfigurationProperties` classes under `config`.~~
   - ~~Properties are grouped by ownership, for example `JwtProperties`,
     `InternalServiceProperties`, `DownstreamServiceProperties`,
     `KafkaProperties`, `MqttProperties`, and service-specific feature
     properties.~~
   - ~~Defaults live in properties classes or configuration files, not in
     scattered constructors.~~
   - ~~Production Java code no longer contains direct `@Value` usage outside
     config.~~

5. ~~Audit and fix N+1 query risks before reshaping packages.~~
   - ~~JPA relationship and repository list endpoints were audited after the
     package reshaping.~~
   - ~~Existing entity relationships are `LAZY`; no `FetchType.EAGER` usage was
     found.~~
   - ~~List endpoints found by the audit either read simple entities without
     related traversal or already use explicit repository queries.~~
   - ~~The structure checker keeps future package changes visible; query-count
     tests should still be added with future endpoints that traverse
     relationships.~~

6. ~~Fix IDE-reported structural inspections as part of each service cleanup.~~
   - ~~Unused imports and redundant production injection annotations were
     removed while touching the services.~~
   - ~~Package-private helper visibility that broke after package moves was
     corrected where needed.~~
   - ~~The cleanup stayed structural; behavior changes were avoided.~~

### Medium

7. ~~Standardize service locations.~~
   - ~~Backend microservices are under `backend/services`.~~
   - ~~Shared backend libraries are under `backend/common`.~~
   - ~~Deploy files, generated files, service code, and shared code remain in
     separate top-level locations.~~

8. ~~Decide and document allowed package exceptions.~~
   - Layered style example:
     `controller/DeviceController`, `service/DeviceService`,
     `repository/DeviceRepository`, `domain/Device`.
   - Feature-sliced style example:
     `device/DeviceController`, `device/DeviceService`,
     `device/DeviceRepository`, `device/Device`.
   - ~~The default is the layered style above.~~
   - ~~Feature-sliced packages are allowed only when documented and when they
     reduce navigation cost.~~
   - ~~The rule is documented in `docs/repository-structure.md`.~~

9. ~~Remove empty placeholder packages after the structure is chosen.~~
   - ~~Empty `.gitkeep` placeholders in source package directories were
     removed.~~
   - ~~Only folders with real code or documented extension points remain in
     source.~~

10. ~~Align test package structure with production code.~~
   - ~~Existing service tests remain under the matching service package.~~
   - ~~Test field injection is intentionally allowed by the production-only
     structure checker.~~
   - ~~No new shared test utility location was needed for this cleanup.~~

11. ~~Add a short repository structure document.~~
   - ~~Added `docs/repository-structure.md`.~~
   - ~~It documents backend services, shared backend code, Java package layers,
     and allowed package exceptions.~~

12. ~~Add automated structure checks.~~
   - ~~Added `scripts/check-project-structure.ps1` as an equivalent static
     structure check.~~
   - ~~It checks that controllers live in `..controller..`.~~
   - ~~It checks that JPA entities live in `..domain..`.~~
   - ~~It checks that repositories live in `..repository..`.~~
   - ~~It checks that production code has no Spring injection annotations.~~
   - ~~It checks that production code does not introduce direct `@Value` usage
     outside configuration classes.~~

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
