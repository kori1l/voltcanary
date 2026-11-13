## ADDED Requirements

### Requirement: Typed device creation intent

AI SHALL compile a bounded CREATE_DEVICE intent using real authorized type/location choices and SHALL NOT accept model-supplied ownership or credentials.

#### Scenario: Ambiguous location

- **WHEN** a request names multiple matching locations or omits a required device type
- **THEN** the assistant asks for a concrete choice and performs no creation before review.

### Requirement: Existing domain access enforcement

Device creation SHALL use the verified user's ordinary device API and its existing account and location checks.

#### Scenario: Foreign location substitution

- **WHEN** a user or model substitutes another account's location in a task
- **THEN** no foreign data is disclosed and no device is created using that location.

### Requirement: Idempotent device creation

The device creation API SHALL deduplicate keyed creation atomically with the resource mutation and SHALL reject conflicting reuse of a key.

#### Scenario: Concurrent or recovered creation

- **WHEN** identical keyed requests execute concurrently or repeat after a lost response
- **THEN** exactly one device exists and authorized replay returns the same resource identity.

### Requirement: Honest setup and review

The assistant SHALL show actual proposed values before mutation and SHALL keep credentials and implicit simulator activation outside the real-device workflow.

#### Scenario: Create without an attached source

- **WHEN** a user confirms a real device without a source
- **THEN** the result links to the created device and source setup without claiming live measurements or inventing manufacturer/model values.

### Requirement: Measured small-model suitability

Delivery SHALL include deterministic intent fixtures and an explicitly recorded evaluation of the configured small model against the frozen acceptance set.

#### Scenario: Model does not meet acceptance

- **WHEN** the chosen model fails the declared quality thresholds or is unavailable
- **THEN** the result records failure or blockage without silently switching to a stronger model or claiming successful acceptance.
