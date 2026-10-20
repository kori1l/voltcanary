## ADDED Requirements

### Requirement: Evidence-based dashboard efficiency investigation
The investigation SHALL measure the browser, dashboard composition, downstream reads and SQL paths using a reproducible scenario matrix. It SHALL assess N+1, duplicate work and unused data without asserting bottlenecks from code inspection alone. It SHALL distinguish production behavior from development effect replay and document sample counts, cold/warm conditions, fixture sizes and unverified scenarios.

#### Scenario: Request count grows with fleet size
- **WHEN** equivalent dashboard queries are evaluated at multiple authorized fleet and widget sizes
- **THEN** the report records calls by layer and unique read key, distinguishes necessary bounded fanout from redundant lookups, and supports or rejects the N+1 hypothesis with traces.

#### Scenario: Payload contains more than the rendered content
- **WHEN** returned fields, rows, series and points are compared with actual widget usage
- **THEN** the report identifies measurable overfetching or records that none was observed, and proposed reductions preserve complete aggregate denominators, units, peaks, exclusions and authorized scope.

### Requirement: Standard optimization checklist coverage
The investigation SHALL assess request batching and deduplication, caching and invalidation, data bounds and aggregation, SQL plans and indexes, concurrency and connection pools, polling and retry behavior, cancellation and response races, JS delivery, React rendering and chart cost. Each checklist item SHALL have an evidence-linked verdict or an explicit unverified or not-applicable status. Hypothetical benefits SHALL remain identified as hypotheses.

#### Scenario: Optimization candidate lacks measured support
- **WHEN** caching, memoization, virtualization, an index or another optimization is proposed without supporting measurements
- **THEN** the report marks its benefit unverified and states how to measure it instead of claiming it is required or already effective.

### Requirement: Rendered dashboard loading strategy assessment
The investigation SHALL compare retaining the authorized rendered canvas during refresh, reusing known layout and evidence on revisit, and progressively filling a layout skeleton on initial loading. It SHALL define behavior for changed filters, revisions, dashboards, accounts and access grants, including cache validation, stale evidence disclosure, independent panel errors and obsolete-response rejection.

#### Scenario: Same-context refresh has a slow source
- **WHEN** a dashboard already has authorized rendered results and a dependency becomes slow or unavailable during refresh
- **THEN** the proposed strategy explains how successful panels remain usable with explicit freshness or refresh state rather than replacing the entire dashboard with a generic Loading screen.

#### Scenario: Account or authorized context changes
- **WHEN** account, scope, revision or access rights change before cached results are reused
- **THEN** the proposed strategy prevents obsolete or unauthorized values and entity names from being presented as current data, with an explicit purge and renewed-access policy.

### Requirement: Actionable optimization research deliverable
The completed investigation SHALL produce one indexed report containing baseline measurements, reproducible evidence, checklist verdicts, limitations and prioritized required optimizations. Each recommendation SHALL specify the affected path, recommended implementation, alternatives, expected measurable effect, confidence, risks, effort, dependencies and acceptance checks. Product optimizations SHALL be scoped as subsequent implementation changes rather than declared implemented by this research.

#### Scenario: Research is ready for implementation planning
- **WHEN** this research change is marked complete
- **THEN** a reviewer can trace each required optimization to evidence and select implementation order and verification criteria, while unsupported and unnecessary optimizations are explicitly separated.
