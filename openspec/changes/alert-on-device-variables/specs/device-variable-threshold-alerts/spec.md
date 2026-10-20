## ADDED Requirements

### Requirement: Source-bound variable rules

Alert-service SHALL own versioned ABOVE_THRESHOLD and BELOW_THRESHOLD rules referencing an authorized numeric variableId, explicit source and compatible threshold unit.

#### Scenario: Two sensor sources

- **WHEN** a device has two temperature sources
- **THEN** a rule evaluates only the explicitly bound source

#### Scenario: Unsupported unit

- **WHEN** a rule threshold has an incompatible unit
- **THEN** the rule is rejected rather than implicitly converted or applied

### Requirement: Exact live evaluation

Analytics SHALL evaluate exact valid LIVE variable observations without sampled chart data or duplicate legacy/ML recalculation and SHALL reject current matches older than the explicit rule maxLiveAgeSeconds policy (default 600), retaining original observation time on retries.

#### Scenario: Short spike

- **WHEN** one live sample exceeds a threshold but its chart bucket average does not
- **THEN** the threshold evaluator still detects the sample

#### Scenario: Backfill sample

- **WHEN** a historical imported sample exceeds the threshold
- **THEN** it does not create a live variable anomaly or alert

### Requirement: Traceable idempotent rule evidence

Evaluation and outgoing anomaly evidence SHALL include source measurement, variable/revision, rule/version, value/unit and lineage with atomic idempotent publication.

#### Scenario: Duplicate delivery

- **WHEN** a matched measurement is consumed repeatedly
- **THEN** one logical evaluation/anomaly exists and lifecycle dedup prevents duplicate delivery requests

#### Scenario: Formula update

- **WHEN** the same variable receives a value calculated under a new revision
- **THEN** evidence identifies that revision while retaining compatible variable rule identity

### Requirement: Failure and maintenance safety

Missing/invalid/unbound or inaccessible evidence SHALL NOT become a numeric match; rule dependency failures SHALL remain retryable/unavailable and existing maintenance policy SHALL apply.

#### Scenario: Rule service unavailable

- **WHEN** the required rule snapshot cannot be obtained
- **THEN** the event is retried or diagnosed unavailable instead of succeeding with an empty rule list

#### Scenario: Maintenance exclusion

- **WHEN** an otherwise matching observation lies inside an excluded or suppressed maintenance window
- **THEN** existing analytics exclusion and alert suppression semantics are preserved

### Requirement: Full-resolution dry run and lifecycle compatibility

Dry run SHALL use the live predicate on bounded full-resolution authorized measurements; alert lifecycle, cooldown and notification ownership SHALL remain in alert-service.

#### Scenario: Dry-run parity

- **WHEN** dry run and live evaluation inspect the same valid sample and rule version
- **THEN** their threshold decisions agree

#### Scenario: Insufficient evidence

- **WHEN** the selected dry-run range contains no eligible measurements
- **THEN** the result reports unavailable or insufficient data rather than a successful healthy state
