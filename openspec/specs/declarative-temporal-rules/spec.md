# declarative-temporal-rules Specification

## Purpose
TBD - created by archiving change compile-declarative-temporal-rules. Update Purpose after archive.
## Requirements
### Requirement: Explicit VCL temporal semantics and execution grants

Rules SHALL compile only VCL registered signatures and execute under a revocable AutomationGrant with pinned configuration, effective/recorded reference cutoffs, explicit unknown/error semantics and boundary-controlled deployment cutover.

#### Scenario: Historical correction
- **WHEN** a corrected assignment or break changes a previously evaluated window
- **THEN** a bounded append-only reevaluation updates one logical alert while original-cutoff output remains reproducible

#### Scenario: Owner access revoked
- **WHEN** a worker would emit an effect after its grant epoch was revoked
- **THEN** the effect is fenced or denied despite the rule having been previously prepared

#### Scenario: Overlapping deployments
- **WHEN** a rule revision changes during an open session
- **THEN** the existing session drains on its pin and replacement does not create a second live alert

### Requirement: Generic typed plan compilation

Rules SHALL compile from versioned declarative graphs with registered operators and type/ref/unit/access/state budgets, rejecting unsupported or unbounded programs.

#### Scenario: Different scenarios
- **WHEN** visit compliance and cumulative work duration are configured
- **THEN** both compile through the same operator registry without HSL_STOP or OVERTIME evaluator branches

#### Scenario: Unbounded query
- **WHEN** a plan uses arbitrary SQL or an unbounded relation/window
- **THEN** publication fails with a typed diagnostic

### Requirement: Temporal evidence and unknownness

Evaluation SHALL preserve effective-time relations, interval union semantics, observed coverage, late events and explicit UNKNOWN rather than equating missing data with false or zero.

#### Scenario: Overlapping work
- **WHEN** one employee has overlapping work records across vehicles
- **THEN** union-duration counts each elapsed instant once and explicit break policy applies

#### Scenario: Bounded supplied interval arithmetic
- **WHEN** a pure kernel reduces an authorized finite batch for one subject/window
- **THEN** it clips and unions included intervals, subtracts only intersecting exclusions, retains UNKNOWN for unresolved selected intervals/predicates, and rejects invalid values or execution-budget overflow without claiming source/reference completeness

#### Scenario: Unobserved action
- **WHEN** a session ends with a disqualifying source gap
- **THEN** the rule cannot claim a proven absence

#### Scenario: Bounded supplied window reduction
- **WHEN** a pure kernel reduces a finite typed single-subject batch for a compiled window
- **THEN** COUNT/SUM/MIN/MAX/AVG use exact half-open membership, preserve checked types/units and decimal policy, retain selected UNKNOWN/null measurements, reject invalid values or budget overflow, and grant no completeness, finalization or emission authority

#### Scenario: Bounded supplied duration witness
- **WHEN** pure witness arithmetic evaluates an explicitly partitioned supplied batch for observed_within or sustained
- **THEN** zero duration requires an actual eligible TRUE observation, positive duration requires actual endpoints and a pinned bounded hold contract, false/unknown/gaps cannot bridge episodes, and no result establishes coverage, finalization, absence or external effect authority

### Requirement: Durable reviewed activation

Compiled rules SHALL use reviewed revisions, evaluator readiness/checkpoints and idempotent evidence-backed alert effects with append-only correction history.

#### Scenario: Projection lag
- **WHEN** the owner accepts activation before referenced projections are ready
- **THEN** status remains ACTIVATING until durable readiness ACK

#### Scenario: Correction after restart
- **WHEN** a corrected interval arrives after a finalized alert and restart
- **THEN** the original evidence is retained and one logical alert is corrected without duplicate effects

#### Scenario: State and input receipt atomicity
- **WHEN** keyed state/inbox infrastructure commits a supplied transition or retries after an uncertain acknowledgement
- **THEN** exact input revisions replay their original receipt, changed transitions conflict, stale or expired lease fences cannot change state, and state plus inbox commit atomically without qualifying timers, outbox or evaluator authority

#### Scenario: Corrected durable timer candidate
- **WHEN** a timer discovered before a correction is later submitted for consumption
- **THEN** the current key lease, schedule revision, saved timer digest and database due time are rechecked, state/schedule/input receipts commit atomically, and obsolete candidates cannot manufacture an observed exit or finalized window

#### Scenario: Atomic immutable output metadata
- **WHEN** the keyed infrastructure appends output intents or records owning receipt acknowledgements
- **THEN** output identity/content, rekey scope and lease fences are checked, state/timers/inbox/outbox roll back together on failure, exact replay preserves original records, and stored output or ACK metadata grants no external effect or notification authority

#### Scenario: Atomic local rekey consumption
- **WHEN** keyed infrastructure consumes a retained REKEY output into a supplied target transition
- **THEN** it verifies exact source-qualified input identity, target and execution pins under ordered source/target lease fences, commits target state/timers/outputs plus immutable local receipt and source ACK atomically, and replays only that verified receipt without granting evaluator or external effect authority

#### Scenario: Durable typed witness result replay
- **WHEN** the bounded keyed witness profile consumes an observation revision or retries after a lost commit acknowledgement
- **THEN** typed state, input receipt and immutable result intent commit atomically, replay verifies input and output-set digests and returns the original result after later corrections, and legacy receipts cannot acquire fabricated input/result bindings or autonomous owner/effect authority

### Requirement: Deterministic lifecycle and calendar boundaries

Temporal kernels SHALL use the root lateness/skew/idle contract, same-session witnesses, half-open calendar windows and explicit pause/resume epochs without implicit catch-up effects.

#### Scenario: Revision two implementer boundary
- **WHEN** a rule pauses with an open session or a month crosses an offset transition
- **THEN** unfinished state becomes UNKNOWN(PAUSED), resume uses a new epoch, and local-month evaluation counts actual elapsed time

#### Scenario: Pure window assignment and replay
- **WHEN** a compiled window_aggregate declaration is projected into a window-assignment plan
- **THEN** exact half-open microsecond boundaries, bounded rolling fan-out and explicit local calendar/week rules are pinned with the kernel/timezone-rules versions; replay rejects changed pins and assignment alone supplies no aggregation or completeness claim

#### Scenario: Pure timing frontier and unknown progress
- **WHEN** root timing arithmetic receives the exact pinned set of qualified source/reference progress
- **THEN** it quarantines overskew future progress, subtracts root lateness once from the minimum trusted frontier, preserves unknown required progress, and treats idle expiration as an UNKNOWN wait outcome without granting coverage or finalization authority

### Requirement: Exact witness and occurrence input contract

A zero-duration observed_within SHALL require an actual eligible witness; stronger dwell/speed predicates SHALL require explicit policy. Evaluation SHALL preserve pinned active visit identity and distinguish observed exit from no observed entry/non-arrival.

#### Scenario: Original bus request boundary
- **WHEN** a short OPEN occurs or the vehicle never enters the configured zone
- **THEN** zero-duration witness may satisfy service; the exit rule cannot invent an arrival, covered exit or non-arrival obligation

