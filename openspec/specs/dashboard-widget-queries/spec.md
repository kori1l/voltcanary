# dashboard-widget-queries Specification

## Purpose
TBD - created by archiving change add-dashboard-widget-query-engine. Update Purpose after archive.
## Requirements
### Requirement: Typed authorized query context

Widget queries SHALL use validated server manifests and an authorized saved revision or draft, intersect dashboard, runtime and widget scope with current resource access, and resolve one explicit time context with asOf and contextHash.

#### Scenario: Dynamic and fixed device bindings

- **GIVEN** a location filter changes and a fixed kitchen widget exists
- **WHEN** the dashboard is queried
- **THEN** dynamic panels use the new authorized location, fixed bindings retain their visible override and no foreign UUID expands access.

#### Scenario: Calendar DST and late response

- **GIVEN** Today is selected in Europe/Belgrade while an older context is in flight
- **WHEN** the query is resolved across DST or the old response returns
- **THEN** all inherited widgets share correct UTC calendar boundaries and obsolete contextHash results cannot replace the new context.

### Requirement: Full aggregate denominators and evidence semantics

Counts and reducers SHALL use complete authorized scope or explicitly partial coverage, distinguish snapshot from event interval, and preserve source units, quality, exclusions, freshness policy and prediction semantics.

#### Scenario: Fleet larger than current UI limit

- **GIVEN** 250 authorized devices exist
- **WHEN** fleet count and critical incident panels are queried
- **THEN** counts cover the full authorized set or explicitly report evaluated/requested coverage; notification attempts are not counted as incidents.

#### Scenario: Sparse cadence and unavailable predictions

- **GIVEN** a manual source has no recent sample and prediction data is unavailable
- **WHEN** the panel evaluates evidence
- **THEN** freshness follows its explicit cadence policy, unknown is not healthy zero, and confidence is not displayed as measured accuracy.

### Requirement: Independent read-only panel results and secure cache

Query composition SHALL isolate per-panel failures, enforce bounded fanout and cache partitioning by authorization and context, and SHALL NOT trigger recalculation, inference jobs, source rotation or notifications.

#### Scenario: One dependency fails

- **GIVEN** telemetry succeeds while analytics fails
- **WHEN** a batch refresh completes
- **THEN** telemetry remains visible and analytics is unavailable or labeled stale only under the same authorized context.

#### Scenario: Authorization changed before cache hit

- **GIVEN** cached data exists for a previously authorized viewer
- **WHEN** a grant or account is revoked before another request
- **THEN** renewed access checks prevent cached values and unauthorized entity names from being returned.

### Requirement: Manifest capabilities and measured query budgets

Catalog capabilities SHALL expose supported units, reducers and limits with at most 24 widgets,8series,500scope devices and50detail devices in v1; performance targets SHALL require measured evidence.

#### Scenario: Exceeded widget or scope limit

- **GIVEN** a definition exceeds24widgets or runtime scope exceeds500devices
- **WHEN** validation or query occurs
- **THEN** a typed limit error requests a narrower configuration instead of silent truncation.

#### Scenario: Unsupported metric or widget

- **GIVEN** temperature or a newer typeVersion is absent
- **WHEN** preview or normal query occurs
- **THEN** the missing capability is explained, unsupported data is not fabricated, and supported panels continue to work.

