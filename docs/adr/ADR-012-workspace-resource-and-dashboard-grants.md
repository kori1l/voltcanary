# ADR-012: Workspace membership and independent resource capabilities

Date: 2026-10-10

Status: Accepted for implementation; acceptance gates are tracked in active OpenSpec changes.

## Context

Personal account ownership must remain compatible while multiple users view and
publish a shared dashboard. A dashboard role cannot authorize equipment data or
incident and maintenance actions. Cached results cannot preserve revoked access.

## Decision

Identity owns workspace, active existing-user membership, teams and membership
audit. Device owns consented resource-account associations, explicit device or
location subtree grants, association versions, tariffs and confirmed operational
facts. Dashboard owns its workspace association, audience capability matrix,
per-author drafts, publication history and sharing audit.

Resource actions are `device.read`, `telemetry.read`, `analytics.read`,
`incident.read`, `incident.ack`, `incident.resolve`, `maintenance.read`,
`maintenance.write`, `ml.read`, `ml.write`, `tariff.read`, `tariff.write`,
`operational.read` and `operational.write`. They are explicit user/team grants.
Dashboard capabilities are VIEW, EDIT, PUBLISH, MANAGE_SHARING, ARCHIVE, COPY,
HISTORY and EXPORT. Recipients must have VIEW, but no resource grant is implied.

Current membership and active resource ownership are checked on each workspace
read. Internal lookups require service authentication and the signed current
actor. Gateway strips client-supplied authority metadata. Workspace evidence is
not served from the dashboard response cache. Reads intersect capabilities and
expose only the authorized cohort, with no hidden names or denied counts.

Private dashboards retain the original account-owner model. Shared drafts use
an author-specific ETag and published base revision. Publication locks the
dashboard row and rechecks PUBLISH after external resource validation. A stale
base yields a conflict. Transfer never adopts another author's private draft.

## Consequences

Workspace reads depend on live identity and device authority and fail closed
when those services are unavailable. Owner transfer and access changes are
audited. No invitations or email messages are sent by membership APIs.
Existing ML write endpoints still use their existing personal authorization;
the presence of a resource `ml.write` grant alone does not enable those endpoints.

Tariffs and operational facts are immutable revisions. Flat tariff cost is an
estimate with explicit currency, tax policy and energy coverage, excluding fixed
fees and exchange. Only confirmed source events establish cycles or downtime;
telemetry absence does not establish a downtime denominator.
