## ADDED Requirements

### Requirement: Understandable variable editor

The console SHALL let authorized users define numeric variables on device types using simple conversion/scale/offset controls or bounded arithmetic, with explicit source/input/unit selection.

#### Scenario: Temperature setup

- **WHEN** a user configures raw 235 with scale 0.1 and offset -0.8
- **THEN** the editor previews intermediate steps and final 22.7 Celsius without requiring executable code

#### Scenario: Ambiguous binding

- **WHEN** a type has multiple candidate sources
- **THEN** the editor requires explicit resolution and displays UNBOUND rather than choosing silently

### Requirement: Fresh server preview before publication

Publication SHALL require a current successful server preview for the same draft/input/source/unit context and SHALL ignore stale asynchronous responses.

#### Scenario: Edit after preview

- **WHEN** a coefficient or input changes after preview success
- **THEN** preview becomes stale and publication remains blocked until refreshed

#### Scenario: Racing preview

- **WHEN** an older preview response arrives after a newer draft was previewed
- **THEN** the older response cannot replace the current result or enable publication

### Requirement: Honest activation and rollout state

The console SHALL distinguish persisted publication from applied processing generation and expose per-source rollout and calculation failures.

#### Scenario: Propagation delay

- **WHEN** publish succeeds before ingestion/telemetry applies the bundle
- **THEN** the source displays PROPAGATING rather than ACTIVE

#### Scenario: Partial rollout

- **WHEN** one device activates and another has invalid source inputs
- **THEN** the console displays independent outcomes and a resumable correction path

### Requirement: Unified variable use

Telemetry, dashboard and threshold-rule selectors SHALL use authorized server descriptors with stable identity/source/unit and explicit measured/computed provenance.

#### Scenario: New variable selection

- **WHEN** an active derived variable has accepted measurements
- **THEN** it is selectable for history, graph, dashboard and supported threshold rules

#### Scenario: Missing value

- **WHEN** a variable has no valid current result
- **THEN** the console shows missing or failed evidence instead of zero or healthy status

### Requirement: Verified distributed acceptance

Delivery SHALL verify the two-device conversion-to-alert workflow, version replay/restart safety, legacy compatibility and declared cost measurements before completion.

#### Scenario: Pinned replay

- **WHEN** an old event is replayed after formula revision and service restart
- **THEN** its old result remains reproducible and creates no duplicate live lifecycle action

#### Scenario: Recorded acceptance

- **WHEN** the implementation is proposed for archive
- **THEN** real broker/database/UI evidence and limitations are recorded and all required validations pass
