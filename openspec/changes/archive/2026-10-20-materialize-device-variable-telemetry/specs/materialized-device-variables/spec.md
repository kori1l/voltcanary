## ADDED Requirements

### Requirement: Bounded deterministic evaluation

Telemetry-service SHALL evaluate pinned numeric affine/arithmetic expressions using only observed inputs of one accepted source message and enforce the documented language, unit and operation budgets.

#### Scenario: Affine conversion

- **WHEN** sensorTemperatureRaw is 235 and the pinned expression is x*0.1-0.8
- **THEN** the stored output is 22.7 in the declared Celsius unit with the pinned revision

#### Scenario: Unsupported computation

- **WHEN** a definition attempts history access, another computed variable or executable calls
- **THEN** validation rejects it and no live computation runs

### Requirement: Per-variable failure isolation

Missing inputs, invalid units or outputs, division by zero and exceeded budgets SHALL produce explicit nonnumeric failure outcomes without discarding valid sibling measurements.

#### Scenario: Zero divisor

- **WHEN** one variable divides by zero and another has valid inputs
- **THEN** only the failing result is absent and diagnosed while valid input and sibling output persist

#### Scenario: Absent input

- **WHEN** a required alias is absent in the accepted message
- **THEN** no last-value substitute or zero is invented

### Requirement: Unified measured and computed persistence

Observed custom numeric fields and derived outputs SHALL be stored through telemetry-owned measurement paths with stable variable identity, immutable revision and retained lineage.

#### Scenario: Custom-only message

- **WHEN** an accepted message has configured numeric fields but no GPS or built-in metric
- **THEN** its observed numeric measurements and eligible derived outputs are persisted

#### Scenario: Historical traceability

- **WHEN** a formula is edited after its output was stored
- **THEN** the old result remains linked to its original revision, unit and input evidence

### Requirement: Atomic idempotent publication

Measurement results, failure outcomes, processing markers and accepted-event outbox SHALL commit atomically and remain idempotent on duplicate source events.

#### Scenario: Transaction retry

- **WHEN** a database transaction fails before commit and is retried
- **THEN** it produces one committed result set and logical outgoing event

#### Scenario: Backfill

- **WHEN** a historical import produces valid variable values
- **THEN** they are marked BACKFILL and are ineligible for live variable alerts

### Requirement: Shared side-effect-free preview

Authorized preview SHALL use the production evaluator and unit rules with explicit bounded inputs and SHALL NOT persist telemetry or publish live events.

#### Scenario: Preview parity

- **WHEN** the preview and live message use identical inputs and pinned evaluator contract
- **THEN** their values and diagnostics agree

#### Scenario: Foreign source preview

- **WHEN** a caller previews against an inaccessible source
- **THEN** the request is denied without revealing source values or creating side effects
