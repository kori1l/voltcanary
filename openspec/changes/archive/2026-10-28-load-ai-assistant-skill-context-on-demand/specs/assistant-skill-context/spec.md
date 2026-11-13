## ADDED Requirements

### Requirement: Explicit conversational memory for model interpretation
Planner and skill requests SHALL include the current raw message and a bounded relevant dialogue projection containing prior messages, the exact pending question and choices, resource chronology and focus. The server SHALL preserve original goals, receipts and cross-plan resource identities, explicitly identify history omissions and SHALL NOT interpret free-form replies by question-text matching.

#### Scenario: Short answer after reload
- **WHEN** the user answers "За сутки" to a persisted period question after reload
- **THEN** the skill model receives the current answer, actual earlier question with choices, original dashboard request and confirmed device/source facts without requiring another planner call solely to recover the question

#### Scenario: Two created devices
- **WHEN** the user says "создай для него дашборд" after creating two devices
- **THEN** the model sees relevant ordered results and explicit focus and either selects a supplied authorized identity or asks for clarification; truncation cannot silently manufacture a unique candidate

### Requirement: Progressive server-owned skill context
The assistant SHALL load the selected skill and only its required authorized facts, choices, retained goals and parameter rules, with explicit snapshot and completeness metadata.

#### Scenario: Dashboard edit
- **WHEN** the current skill moves existing widgets
- **THEN** device-creation instructions and unrelated catalogues are not sent, while target identity and preservation requirements remain available

#### Scenario: Parameter proposal
- **WHEN** an optional parameter is missing and its contract permits model suggestions
- **THEN** the model can propose a value without a regex extractor or a fabricated mandatory question

### Requirement: Bounded authorized context continuation
Additional context SHALL be obtained only through registered typed loaders and scoped cursors under the same attempt budget; mandatory context SHALL NOT be silently omitted.

#### Scenario: Need actual source fields
- **WHEN** a model requests more choices
- **THEN** the application validates a registered loader and current access before loading, then accounts for any model continuation

#### Scenario: Insufficient capacity
- **WHEN** required instructions and pending goals do not fit
- **THEN** the turn reports insufficient context or requests narrowing rather than accepting a degraded hidden truncation

#### Scenario: Unavailable source
- **WHEN** the capability service cannot be read
- **THEN** no fields are invented and technical dependency failure is not presented as missing user meaning
