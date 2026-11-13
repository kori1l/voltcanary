## ADDED Requirements

### Requirement: Generic capability ownership replaces specialized implementation

The retained transit scenarios SHALL be implemented through VCL generic sessions/absence/evidence and alert lifecycle (G0/G4), with a transit template (G5). This superseded delta SHALL NOT introduce transit-only core tables, an independent engine/ledger or a hardcoded required-stop condition type. Normative VCL/storage/import/adapter contracts apply; old scenario semantics remain acceptance constraints rather than a separate implementation queue.

#### Scenario: Transit scenario is implemented
- **WHEN** a team implements the retained HSL example
- **THEN** it uses the current generic capability owners and actual source evidence, preserving mandatory policy and unknownness without reviving T1–T7 as separate work



### Requirement: Typed reviewed stop-service rule

The platform SHALL prepare and activate only authorized typed rules binding exact source, trip scope, required-service policy, zone-set revision, service predicate and coverage thresholds.

#### Scenario: No mandatory policy
- **WHEN** activation lacks an approved required-stop subset
- **THEN** the rule remains inactive and returns a structured missing-policy diagnostic

#### Scenario: Concurrent configuration
- **WHEN** a pinned policy or source binding changes before confirmation
- **THEN** activation rejects the stale review instead of substituting current values

#### Scenario: Evaluator projection not ready
- **WHEN** the activation request is accepted but its pinned projections and start checkpoint are not durably acknowledged by the evaluator
- **THEN** the rule remains ACTIVATING and retry reconciles the same activation epoch without claiming ACTIVE or silently changing the evaluation start boundary

### Requirement: Event-time visit evaluation

A durable evaluator SHALL identify trip stop occurrences and require observed entry, a confirmed 30m exit, sufficient observation coverage and elapsed lateness before POSSIBLE_MISSED_REQUIRED_STOP.

#### Scenario: Door opening
- **WHEN** valid stationary OPEN evidence occurs inside the visit
- **THEN** the result is SERVICED even if the door is CLOSED at exit

#### Scenario: No stopping
- **WHEN** a bus enters 30m, never stops or opens and exits with adequate evidence
- **THEN** a possible-miss outcome is emitted without requiring a stop or 10m entry

#### Scenario: Missing entry or feed
- **WHEN** the first sample is inside or the feed goes silent
- **THEN** the visit becomes left-censored or UNKNOWN and cannot create a missed-service alert

### Requirement: Durable idempotence and corrections

The system SHALL preserve visit state, timers and append-only outcomes through restart/replay and update one logical alert per actual trip occurrence.

#### Scenario: Late door
- **WHEN** an in-window service witness arrives before the 10s lateness deadline
- **THEN** it prevents a possible-miss alert

#### Scenario: Post-final correction
- **WHEN** relevant service evidence arrives after finalization
- **THEN** an auditable correction updates the existing outcome/alert without a duplicate notification

#### Scenario: Stop repeated
- **WHEN** a loop revisits one stop under a new stopSequence
- **THEN** the visits remain independently evaluated

### Requirement: Evidence and policy limitations

Alerts SHALL expose bounded source-linked evidence and uncertainty, respect exemption/access rules and describe possible missed observation rather than proven physical misconduct.

#### Scenario: Gap or jitter
- **WHEN** coverage exceeds its maximum gap or geometry is uncertain
- **THEN** the outcome is UNKNOWN rather than a violation

#### Scenario: Not required
- **WHEN** a nearby stop is not mandatory for the selected trip policy
- **THEN** no service violation is produced

#### Scenario: Disabled or maintenance
- **WHEN** the rule is disabled or the visit is exempt before delivery
- **THEN** delivery is suppressed with an auditable reason


