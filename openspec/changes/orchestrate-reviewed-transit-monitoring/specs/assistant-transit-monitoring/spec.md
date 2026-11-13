## ADDED Requirements

### Requirement: Generic capability ownership replaces specialized implementation

The retained transit scenarios SHALL be implemented through Generic VCL/adapter/import authoring in the existing assistant (G7). This superseded delta SHALL NOT introduce transit-only core tables, an independent engine/ledger or a hardcoded required-stop condition type. Normative VCL/storage/import/adapter contracts apply; old scenario semantics remain acceptance constraints rather than a separate implementation queue.

#### Scenario: Transit scenario is implemented
- **WHEN** a team implements the retained HSL example
- **THEN** it uses the current generic capability owners and actual source evidence, preserving mandatory policy and unknownness without reviving T1–T7 as separate work



### Requirement: Grounded dependent transit skills

The assistant SHALL plan registered transit/device/source/zone/policy/rule/dashboard steps using authorized evidence and successful receipts, retaining mandatory goals and user-only fact provenance.

#### Scenario: Ambiguous bus
- **WHEN** the user names a line with several physical vehicles or directions
- **THEN** the assistant asks with actual scoped choices and creates no resources

#### Scenario: Partial execution
- **WHEN** device creation succeeds but connection or zone creation fails
- **THEN** resume uses the existing device receipt and completes pending steps without duplicates

#### Scenario: Missing obligation
- **WHEN** the user requests stop alerts without specifying mandatory stops
- **THEN** the assistant obtains an explicit policy choice instead of deriving obligations from GTFS

### Requirement: Bounded coordinator-owned research

Specialist jobs SHALL return typed scoped evidence/questions through one durable coordinator with bounded depth, concurrency, downloads, cancellation and prompt-injection isolation.

#### Scenario: External instruction
- **WHEN** a fetched source requests ignoring access rules or posting credentials
- **THEN** the content is treated as untrusted evidence and grants no command or egress permission

#### Scenario: Late worker
- **WHEN** a cancelled worker returns after the user changes the trip
- **THEN** its output cannot replace the newer accepted review

### Requirement: Qualified per-skill routing and shared cost

All model calls SHALL use existing durable reservations and explicit PINNED/AUTO policy; AUTO assignments require authorized qualified profiles, actual capability support and an explicit price/cap policy.

#### Scenario: Different complexity
- **WHEN** research and a schema-filling step have separately qualified profiles
- **THEN** AUTO records their distinct durable assignments without changing tool permissions

#### Scenario: Pinned failure
- **WHEN** a pinned model cannot fit context or becomes unavailable
- **THEN** the assistant returns a typed failure without silently switching models

#### Scenario: Budget exhausted
- **WHEN** a child research or assessment call would exceed the parent ceiling
- **THEN** the call is stopped before network I/O and completed domain receipts remain available

### Requirement: Reviewed completion and replay

Only receipt-backed commands SHALL establish execution success; activation and materially changed configuration require valid consent, and completed results SHALL replay without generation availability.

#### Scenario: Disabled model
- **WHEN** a previously completed requestId is repeated after its model is disabled
- **THEN** authorized identical input returns the stored result and changed input conflicts

#### Scenario: Changed stops
- **WHEN** a snapshot or mandatory subset changes after review
- **THEN** the stale consent cannot activate the new rule

#### Scenario: Unknown data
- **WHEN** the selected source has no current door/GPS evidence
- **THEN** the assistant reports waiting or unsupported capability and never simulates a live success


