# ADR-011: Private dashboard definitions in a dedicated service

Date: 2026-10-08. Accepted; local Compose implementation and authorization smoke verified.

## Context

The fixed fleet overview owns no persistent personal definitions. Private drafts,
immutable revisions, preferences and publication concurrency need an independent
transaction boundary. They must not become responsibilities of analytics or the
device account service, and must not own raw telemetry or copied credentials.

## Decision

Create `backend/services/dashboard-service`, HTTP port 8089, PostgreSQL database
`voltcanary_dashboard`, with Flyway-managed tables. Controllers, HTTP ownership
clients and JDBC persistence are adapters; application use cases depend on ports
for ownership and storage. Dashboard definitions reference evidence by typed IDs
and query intents; later widget execution uses authorized read ports, never direct
business databases or arbitrary SQL/HTTP supplied in definitions.

Private ownership is the current authenticated user plus their active device
account. Validate the signed bearer token, consult identity `/auth/me` on every
request to catch disabled users, and resolve `/accounts/current` server-side.
Require matching user IDs and ACTIVE status; failures close access, with no cached
permission fallback. ADMIN/SUPPORT receive no private-owner bypass. Client owner
and account fields cannot influence ownership. Foreign IDs return missing404.

Definitions have schemaVersion, name, description, defaultContext, sections,
widgets and layouts. Known structures have explicit bounds; unknown widget types
and versions remain safe opaque JSON and inert. Reject executable content,
credentials and free HTTP/SQL definitions recursively, including unknown widgets.
Do not persist raw evidence, notification recipients or authentication headers.

Persist dashboard metadata, author drafts (base revision plus ETag), immutable
revisions, personal preferences, lifecycle audit and actor/account/route/body-bound
idempotency outcomes. Create/publish keys live for 24 hours. Renew authorization
before replay; serialize actor writes to enforce the ten-active-dashboard quota
and atomic idempotency. Publication requires If-Match and matching base revision;
stale writes return409, absent preconditions428, invalid fields422. Rollback loads
an authorized historical definition into a new draft; publication appends history.

Archive is soft and clears inaccessible defaults/favorites safely. Restore keeps
identity and observes the active quota. Copy creates a distinct private draft,
copies no grants, and checks referenced resources before retaining their bindings.
Preference reads explain fallback to the existing system overview when a default
is inaccessible. The existing `/dashboard` frontend stays unchanged until catalog
and editor integration; no browser-storage definitions are silently imported.

## Consequences and verification

The service adds one database and deployment to Compose/Kubernetes/Helm. Gateway
routes are additive and allow/expose ETag, If-Match and Idempotency-Key consistently.
Readiness covers database migrations; ownership dependency failures remain explicit
request errors, not a health-only security claim. PostgreSQL integration checks
must cover ownership, lifecycle revocation, quota, concurrent create/publish,
history, stale drafts, expired/mismatched keys, safe unsupported widgets and fresh/
upgrade migration before rollout. No team permissions or public sharing ship here.
