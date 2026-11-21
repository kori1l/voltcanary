## ADDED Requirements

### Requirement: Qualified Java library owns graph execution
The assistant SHALL qualify a released LangGraph4j artifact against the existing Java, Spring Boot and Spring AI baseline before production adoption. The selected library SHALL own graph traversal and conditional transitions; the application SHALL retain typed domain contracts and SHALL NOT maintain a second active handwritten traversal for the same plan.

#### Scenario: Incompatible framework dependency
- **WHEN** the candidate requires an incompatible Spring AI dependency or hidden tool loop
- **THEN** qualification fails with recorded evidence and production dependencies are not silently downgraded

#### Scenario: Superficial library wrapper
- **WHEN** a library node merely calls the unchanged custom graph scheduler
- **THEN** the replacement acceptance criterion remains unmet

#### Scenario: Control-flow cycle versus domain dependency
- **WHEN** a graph returns to clarification, context loading or bounded recovery
- **THEN** A3 typed step dependencies remain acyclic and existing budget/deadline limits are not reset

### Requirement: Single authority across checkpoints and domain records
Graph persistence SHALL preserve versioned account/plan/revision bindings and one authoritative owner per state concern. A graph checkpoint SHALL NOT independently authorize a model dispatch, owner mutation or access to a resource.

#### Scenario: Crash between graph and plan persistence
- **WHEN** a process stops between checkpoint and domain-state writes
- **THEN** atomic persistence or explicit reconciliation restores a consistent revision before further dispatch and does not repeat a confirmed effect

#### Scenario: Concurrent resume
- **WHEN** two workers resume the same paused plan
- **THEN** fresh lease/revision fences admit only the valid transition and preserve original receipts

### Requirement: Graph execution retains model and effect boundaries
Every model exchange SHALL use the existing budget ledger and Spring AI exchange port. Graph replay SHALL retain UNCERTAIN model outcomes and UNKNOWN owner results. Mutations SHALL require fresh access, exact review/version/digest and explicit confirmation.

#### Scenario: Unknown provider result after restart
- **WHEN** the process crashes after sending a model request without a durable response
- **THEN** checkpoint replay does not silently resend the same attempt or erase its reserved usage

#### Scenario: Confirmation pause resumes after access loss
- **WHEN** a previously prepared graph resumes after the actor loses access
- **THEN** the saved checkpoint cannot grant authority and owner application is denied

### Requirement: Existing capabilities permit incremental agent delivery
The graph runtime SHALL support an independently declared existing-capability workflow without requiring completion of all configurable storage or temporal-rule milestones. Unsupported owner capabilities SHALL remain unavailable, with their original acceptance requirements open.

#### Scenario: Dashboard workflow precedes temporal platform
- **WHEN** the graph can use an authorized existing device and ordinary dashboard APIs
- **THEN** clarification, review, confirmation and exact readback can be qualified while full temporal-rule execution remains pending

### Requirement: Specialists share one bounded graph and budget
Consultations SHALL use the qualified graph runtime with bounded depth, fan-out and concurrency, source/revision bindings, cancellation and one root question arbiter. They SHALL NOT create recursive unreserved model calls or a second budget/scheduler authority.

#### Scenario: Specialist asks a follow-up
- **WHEN** a nested specialist identifies a missing required input
- **THEN** the coordinator persists a scoped question and resumes only applicable work after a revision-checked answer

### Requirement: Versioned cutover preserves evidence and compatibility
New plans SHALL pin their engine and graph versions and preserve conversation history, checkpoints and saved review execution across restarts. The runtime SHALL reject silent engine fallback and retain failed quality evidence. Historical pre-release conversation and plan records require no migration. Offline graph acceptance SHALL NOT establish real-model quality or authorize paid trials.

#### Scenario: New engine fails during a pinned plan
- **WHEN** graph execution cannot continue
- **THEN** the system records the failure and keeps the plan pinned to its original engine
