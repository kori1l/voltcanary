# Workspace and dashboard business contracts

Implementation reference: accepted OpenSpec changes for workspace membership,
team publication, tariffs, operational periods and visualization types.
Acceptance evidence is tracked in [the verification flow](../flows/workspace-dashboard-business-verification.md).

## Membership and resource capabilities

All public requests require a signed bearer token. `GET/POST /workspaces` lists
or creates workspaces. `/workspaces/{id}/members` supports list and reviewed
membership updates; `/members/{userId}` supports removal. Teams use GET/POST
`/workspaces/{id}/teams`; transfer uses POST `/workspaces/{id}/transfer`.
Only active existing users can join. Membership management requires owner or
manager; owner departure requires transfer. Audit is GET `/workspaces/{id}/audit`.

Device routes `/workspaces/{id}/resource-accounts`, `/resource-grants` and
`/resource-audit` govern reviewed owner consent and explicit action/scope grants.
Dashboard sharing never creates these grants. `/workspaces/{id}/devices/query`
and `/telemetry|analytics|alerts/workspaces/{id}/query` accept typed scope reads
with explicit workspace/account IDs. Device maintenance writes use
`/workspaces/{workspace}/maintenance/{account}/{device}` and `/{window}/cancel`,
`/{window}/finish`, requiring `maintenance.write`. Incident action routes are
`/alerts/workspaces/{workspace}/{alert}/acknowledge` and `/resolve`, requiring
the corresponding incident action. Private owner endpoints remain separate.

Scope resolution requires active actor, membership, consented active account and
matching device/location grants. Internal authority endpoints require service
authentication. Failures deny access; no implicit ADMIN/SUPPORT resource bypass
exists for these reads. Response scope counts cover authorized equipment only.

## Team dashboard publication

`GET /dashboards/team` returns authorized shared dashboards. POST
`/dashboards/{id}/workspace` associates a reviewed workspace. GET/PUT `/grants`
uses explicit USER/TEAM recipients, capabilities, `expectedVersion` and
`reviewed`. POST `/sharing-preview` accepts `userIds` and reports per-widget
usability without hidden resource names/counts or grants. POST `/transfer`
requires workspace management and a reviewed active new owner; GET
`/sharing-audit` exposes the sharing audit to managers of dashboard sharing.

Drafts are author-specific. EDIT does not imply PUBLISH; publication uses draft
ETag and current published revision with conflicts on concurrent changes. Query
scope uses the viewer's current resource grants. Workspace queries bypass
response caching. HISTORY, COPY, EXPORT and ARCHIVE are independent permissions.
GET `/export` produces a layout template with unbound widgets and cleared
resource queries, payload and navigation. POST `/dashboards/import` requires a
reviewed definition and idempotency key and creates a private unbound draft.

## Tariffs and operations

GET `/energy-tariffs` lists scoped immutable revisions. POST `/energy-tariffs`
or `/revisions` accepts `{tariff, reviewed}`; `version` is the expected previous
version. A tariff contains currency, timezone, decimal `ratePerKWh`, half-open
effective interval, `taxesIncluded` and correction `reason`. POST
`/{id}/archive` prevents future use while preserving past effective evidence.
Time bands, billing, invoices, fixed fees and currency conversion are unsupported.

Cost reads split at tariff revision boundaries and multiply qualified energy
by decimal rates. Rounding occurs after summation, at currency precision with
HALF_EVEN. Missing tariff/energy yields partial or unknown evidence; mixed
currency yields no combined amount. Comparisons require equal duration, complete
coverage and matching currency. The UI always displays Estimate.

GET/POST `/operational-events` reads or appends confirmed facts. POST `/import`
imports up to100 reviewed facts atomically. Source identity is scoped to a
registered source of the device and is idempotent; conflicting retries require
an explicit revision. Kinds: CYCLE, DOWNTIME, PLANNED_DOWNTIME, OBSERVATION;
completeness: COMPLETED, ABORTED, INCOMPLETE. Completed facts require an end.
Corrections require current version and reason. GET/POST `/operating-schedules`
handles timezone, weekday slots, holidays and effective revision intervals.

Schedule DST gaps shift forward and overlaps use the earlier offset. Uptime
uses the union of confirmed observation intersected with scheduled intervals;
overlapping downtime is counted once. Planned downtime remains separate. Energy
per cycle uses only completed, contained cycles with qualified complete energy.
Reads are bounded to31 days,50 devices,500 facts and32 energy subreads.

## Visualizations

Widget manifests advertise `visualizations`; definition validation rejects
incompatible explicit selections. Number, line, area, vertical/horizontal bar,
stacked bar, pie and donut render typed evidence. Fleet slices are disjoint
categories rather than overlapping totals. Pie/donut require finite nonnegative
values; missing or zero total is disclosed. Time charts preserve timestamps,
units, declared reducer and gaps. Multi-series evidence retains its default
presentation when the selected simple diagram cannot represent it. Presentation
choices are saved in drafts and revisions. Legends and calculation annotations
are optional; exceptional states remain visible.

The editor hides publication, copy and export without their independent grants.
Operational schedule corrections preserve the saved timezone, and switching
equipment invalidates pending history responses. Compact operational summaries
retain partial/unknown observation and cycle-energy indicators.
