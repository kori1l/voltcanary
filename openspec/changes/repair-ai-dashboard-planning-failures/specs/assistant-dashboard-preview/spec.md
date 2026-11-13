## ADDED Requirements

### Requirement: Typed configurable failure and mutation recovery

Recovery for configured resources SHALL distinguish VCL/type errors, stale import validation, source schema drift, permission revocation, partial imports and unknown remote writes; it SHALL use the existing A2/V-R coordinator and original owner receipts without retrying mutations as generation repair.

#### Scenario: Source drift
- **WHEN** an external schema changes after review
- **THEN** recovery requests a new mapping/validation review and cannot silently coerce fields

#### Scenario: Write timeout
- **WHEN** an external write may have committed
- **THEN** recovery reconciles the same mutation identity instead of issuing a new operation



### Requirement: First-pass measurement without recovery inflation
Explicit isolated QA planning-only first-pass runs SHALL disable automatic format/preparation/outcome corrections and model judges while preserving basic schema, authorization, confirmation and existing hard safety guards; they SHALL NOT change production defaults or execute domain mutations. Later recovery runs SHALL preserve immutable initial outcomes and separately report first-pass, recovered and final results and all call/token costs.

#### Scenario: Initial candidate fails and correction succeeds
- **WHEN** a recovery-enabled run produces a rejected first candidate followed by a valid correction
- **THEN** first-pass remains failed, recovery and final disposition are recorded separately and all attempts belong to the same original trial and A2 budget

#### Scenario: Diagnostic candidate fails schema
- **WHEN** the first generated candidate fails server validation in the diagnostic profile
- **THEN** the failure is recorded without a model correction or judge call and cannot execute

### Requirement: Single bounded dashboard planning recovery policy
All dashboard interpretation, generation and repair calls SHALL consume the single shared turn budget and parent-plan ceiling owned by the orchestration model-policy service; PINNED SHALL preserve the configured model and AUTO SHALL permit only explicitly authorized recorded routing decisions. Technical repair SHALL NOT bypass ordinary preparation, verification or authorization.

#### Scenario: Nested failure categories occur
- **WHEN** an initial format repair is followed by preparation and outcome failures
- **THEN** their combined actual provider calls remain within the configured shared total budget without resetting counters in helpers or duplicating the reservation ledger

#### Scenario: Explicit automatic routing
- **WHEN** the configured AUTO policy permits a qualified alternate candidate and budget remains
- **THEN** recovery may use that candidate with a recorded reason while preserving state, goals, domain receipts and confirmation requirements

#### Scenario: Same rejected candidate repeats
- **WHEN** a repair returns the same normalized invalid candidate and failure
- **THEN** recovery stops before an unproductive additional attempt and retains the pending request and last accepted review

#### Scenario: Slow provider reply
- **WHEN** a provider call is still pending beyond the ordinary short UI wait
- **THEN** the UI retains honest pending state and no speculative second provider call is launched

### Requirement: Genuine clarification distinct from technical failure
The assistant SHALL ask only for missing user meaning, retain already-confirmed facts and report exhausted technical/provider failures as recoverable failures rather than requests to repair widget schemas.

#### Scenario: Incompatible door chart is repairable
- **WHEN** the model selects an incompatible numeric metric for an available recorded boolean door field
- **THEN** bounded recovery uses the authorized compatible field choice and preserves the explicit dashboard name without asking the user to choose a technical schema

#### Scenario: Actual source ambiguity
- **WHEN** two authorized sources remain equally compatible with the user's request
- **THEN** one meaningful structured source question is returned without losing known facts or selecting a source arbitrarily

#### Scenario: Repair budget is exhausted
- **WHEN** all permitted attempts fail
- **THEN** no candidate becomes READY, the task records a safe retriable failure and the UI retains the prior review without a clarification-default timer

### Requirement: Auditable recovery and measured rollout
The system SHALL correlate actual attempts and final outcomes without credentials/private reasoning, and default rollout SHALL require repeated real-model conversation, browser and confirmed-execution acceptance.

#### Scenario: Final live gate lacks a provider
- **WHEN** the required real-model acceptance cannot invoke the configured provider
- **THEN** default enable is blocked and the report distinguishes availability failure from successful semantic checks

#### Scenario: Planning retry follows failure
- **WHEN** the user retries an unsuccessful refinement
- **THEN** the system reuses saved task facts and pending work, records a new bounded turn, and performs no dashboard mutation before separate confirmation
