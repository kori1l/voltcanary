# ai-device-creation Specification

## Purpose
TBD - created by archiving change create-devices-with-ai-workflows. Update Purpose after archive.
## Requirements
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

### Requirement: Persistent real-device intent
The assistant SHALL preserve explicit name, type and optional device choices
across clarification and SHALL distinguish the original requested quantity from
later alternative names. Ordinary name replies SHALL require no special syntax.
#### Scenario: Repeated name answers
- **WHEN** a user answers a device-name question with a plain name and later replaces that name
- **THEN** one device intent uses the latest name without treating old names as multiple devices

### Requirement: Reviewed supported integration setup
The system SHALL execute supported real-device integration setup separately from
dashboard composition, through authorized domain APIs and a fixed typed adapter.
It SHALL expose actual source readiness, retain created resource identity after
partial failures and reconcile using durable creation identity without duplication.
#### Scenario: Silent real source
- **WHEN** a reviewed HSL source is connected but no fresh accepted GPS observation exists
- **THEN** the result reports waiting for data without claiming live telemetry or creating a simulator
#### Scenario: Uncertain connection response
- **WHEN** source setup has an uncertain outcome after device creation
- **THEN** explicit reconciliation reuses the same device receipt and source identity
#### Scenario: Dashboard compatibility
- **WHEN** a configured device is handed to dashboard creation
- **THEN** existing authorized capability resolution and dashboard review operate on its actual device and source

