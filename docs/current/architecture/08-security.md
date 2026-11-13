# Security

VoltCanary uses the API Gateway as the external HTTP trust boundary and keeps
resource ownership checks in the owning backend services.

## Authentication

Browser and public API traffic enters through `api-gateway`. Protected routes
require an access JWT. The gateway validates issuer, audience, key id, token
type, expiration, and signature. User-facing services independently verify the
same bearer JWT, including on direct diagnostic ports. Gateway identity headers
are transport hints and never an alternative credential.

Incoming `X-User-*` headers from clients are stripped before the gateway sets
derived values. The servlet identity adapter replaces legacy controller inputs
with verified subject/email/role and removes caller account/workspace/capability
headers and internal credentials from user routes. Account identity is resolved
by the owning service; it is not assumed to equal the JWT subject.
Admin routes under `/auth/admin/**` require the `ADMIN` role at
the gateway, and services still enforce resource ownership for domain data.

## Internal Boundaries

Internal service-to-service calls use private network routes and shared
internal service tokens in local development. Production service identity is a
target hardening item documented in
[../../target/production-readiness.md](../../target/production-readiness.md).

`ml-service` requires internal service authentication for inference, training,
run, model, and `/ml/*` routes when configured for production-like operation.
Health endpoints remain open for probes.

Telemetry service reads use `GET /internal/telemetry/devices/{deviceId}/...`
for readings, exports, envelopes, metrics, latest, quality and variable catalogue
reads. These routes require the configured internal service token. The public
`/telemetry/**` routes require user JWTs and cannot be elevated with an internal
token. Analytics materialization and variable-rule validation use the internal
read contracts. User-triggered rule dry runs forward the current user JWT to
analytics instead of reconstructing a user from a header.

## Identity consumer and migration inventory (2026-10-20)

| Surface | Boundary / compatibility migration |
| --- | --- |
| Gateway | Validates JWT and strips supplied identity hints before forwarding; collector routes remain separate. |
| Identity `/auth/**`, `/workspaces/**` | Existing JWT validation for user/admin operations; explicitly public registration/login/refresh/recovery and separately authenticated internal lookups. |
| Device accounts, types, registry, maintenance, business facts, workspace operations | Shared JWT filter plus local `AuthenticatedUserResolver`; header-first resolution removed. Internal registry, lifecycle, source and dashboard reads keep explicit service-token checks. |
| Telemetry `/telemetry/**` | Shared JWT filter supplies verified actor to legacy controllers; domain device/source/workspace access checks remain. Dedicated internal telemetry read aliases replace service-token access through public routes. |
| Analytics `/analytics/**` | Shared JWT filter supplies verified actor/role; ML account lookup remains in device-service. Telemetry clients use internal read routes. |
| Alerts, rules, notification channels/preferences | Shared JWT filter; owning account lookup replaces account hints, including notification settings. Rule evaluation propagates the live user JWT. |
| Simulator `/simulation/**` | Shared JWT filter, owning device checks for config/run reads and writes; run IDs resolve to their device before access. Template/validation operations require JWT without creating resources. |
| AI `/ai-agent/**` | Existing JWT validation; tools forward JWT for simulator validation/templates as well as mutations. |
| Dashboards and preferences | Existing JWT ownership adapter and current account/sharing checks. |
| Ingestion `/ingestion/**` | Collector credentials; user JWTs and internal tokens do not replace source tokens. |
| Health, docs, `/internal/ping` | Public diagnostics; no user identity or resource mutation. Other internal contracts keep their own service authentication. |
| Positive Java HTTP fixtures | `common-test/SignedUserTokens` generates an ephemeral RSA key per JVM; test contexts explicitly configure its public key. Negative header spoofing remains unauthenticated. |
| Variable smoke/recovery/replica/access | Dedicated account registration/login through identity or gateway, bearer JWT for user APIs, collector token only for ingestion. Fixture summaries exclude credentials. |
| Dashboard/workspace, vehicle, ingestion and ML smoke | Existing login-based fixtures; forged headers remain only as negative test inputs or ignored compatibility hints. |

The local Compose diagnostic bindings use `127.0.0.1`. Container DNS access is
unchanged; Kubernetes diagnostic access uses authenticated `kubectl port-forward`.
Neither local binding nor port-forward substitutes for JWT verification.

Isolated Java test keys are not trusted by ordinary application configuration.
There is no header-trust switch. Live automation uses ordinary login/refresh,
creates resources in a dedicated account and cleans up only its tracked IDs.
JWT validation retains the existing expiry semantics: refresh-session revocation
does not instantly revoke an already-issued access JWT. Domain account-state
checks and token expiry remain necessary; see the
[identity contract](../api/identity-auth-contract.md).

### Verification on 2026-10-20

Backend suites ran across all modules with PostgreSQL/Timescale/Redpanda
Testcontainers. The migrated analytics/notification fixtures and shared filter
were rerun after their corrections; the final report set contains 578 tests,
zero assertion failures and zero errors. The full-run log includes the earlier
fixture failures and a Kafka fork-shutdown timeout; focused successful reruns
are retained separately, not overwritten.

The isolated Java 21 Docker stack passed variable base/recovery/replica/access
regressions. `user-identity-docker-smoke.ps1` passed 68 checks for two accounts,
real login/refresh, gateway/direct-port CRUD, forged subject/role/account/workspace
headers, private draft creation/access and tracked cleanup. The isolated stack
was removed after verification.

The main Compose ML gate passed dataset/features/training/S3 artifacts/inference
with `predictionStatus=TRAINED_MODEL` and empty fallback reason. It used
`TIMESCALE_PORT=15434` because Windows rejected the normal host binding at 5433.
ML lifecycle cleanup was not requested or claimed. Ignored evidence:
`artifacts/identity-backend-suite-summary.json`, `identity-*-final.log`,
`user-identity-smoke-summary.json`, and
`identity-ml-production-smoke-summary.json`. OpenSpec strict validation and
repository structure checks passed; pre-existing size/style warnings remain.

## Source Tokens

Collector authentication is separate from user JWTs. Data sources receive
source ingestion tokens from `device-service`; only hashes/prefixes are stored
and later runtime config events expose only the token hash and source metadata
needed by ingestion.

`ingestion-service` validates collector tokens against its local
`source.runtime-config.v1` read model. A token for one source cannot ingest for
another source or device. Disabled, deleted, or account-inactive sources fail
closed after their runtime config reaches ingestion.

## Secrets

Raw secrets must not be stored in general source runtime configuration,
adapter mappings, event payloads, logs, or frontend-readable APIs.

Secret-bearing fields use references such as environment-backed credential
references in local development. AI provider secrets are owned by
`ai-agent-service` and must not be returned after initial submission or
rotation.

## Data Isolation

Domain services enforce account/resource ownership for devices, telemetry,
analytics, alerts, maintenance windows, simulator runs, ML settings, and agent
state. Gateway authentication is necessary but not sufficient for data access.

Events should include account context when available so consumers can enforce
tenant boundaries and audit derived records.

Assistant tasks resolve the actual account with a read-only owned-scope API,
fetch bounded resource summaries using the current JWT, and persist owner/account/
version/digest and audit in PostgreSQL. Browser account/role/name hints cannot
override that scope. Confirmation is invalidated on session/version changes;
execution renews domain access. Stable owning-service receipt keys support explicit
reconciliation of uncertain single-resource creation. Legacy chains never reset
to executable READY after interruption. Task persistence contains no user JWT or
provider/source secret. See [task operations](../operations/ai-reviewed-tasks.md).
