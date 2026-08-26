# Security

VoltCanary uses the API Gateway as the external HTTP trust boundary and keeps
resource ownership checks in the owning backend services.

## Authentication

Browser and public API traffic enters through `api-gateway`. Protected routes
require an access JWT. The gateway validates issuer, audience, key id, token
type, expiration, and signature, then forwards trusted identity headers to
downstream services.

Incoming `X-User-*` headers from clients are stripped before the gateway sets
trusted values. Admin routes under `/auth/admin/**` require the `ADMIN` role at
the gateway, and services still enforce resource ownership for domain data.

## Internal Boundaries

Internal service-to-service calls use private network routes and shared
internal service tokens in local development. Production should replace the
shared local token model with workload identity, mTLS, or another controlled
service identity mechanism.

`ml-service` requires internal service authentication for inference, training,
run, model, and `/ml/*` routes when configured for production-like operation.
Health endpoints remain open for probes.

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
references in local development and should use a managed secret provider in
production. AI provider secrets are owned by `ai-agent-service` and must not be
returned after initial submission or rotation.

## Data Isolation

Domain services enforce account/resource ownership for devices, telemetry,
analytics, alerts, maintenance windows, simulator runs, ML settings, and agent
state. Gateway authentication is necessary but not sufficient for data access.

Events should include account context when available so consumers can enforce
tenant boundaries and audit derived records.

## Production Gaps

- Replace shared internal tokens with workload identity or mTLS.
- Add production secret management and secret rotation procedures.
- Add NetworkPolicies that restrict database, broker, cache, and internal API
  access to the services that need them.
- Define centralized audit export and retention.
- Add MFA/SSO/SCIM/custom RBAC only through explicit product and OpenSpec
  changes.
