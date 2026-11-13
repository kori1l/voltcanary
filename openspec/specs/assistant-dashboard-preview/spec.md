# assistant-dashboard-preview Specification

## Purpose
TBD - created by archiving change establish-ai-dashboard-conversation-evals. Update Purpose after archive.
## Requirements
### Requirement: Reproducible multi-turn dashboard evaluation
The evaluation harness SHALL exercise the actual authenticated dashboard conversation path using versioned scenarios and fresh isolated state, and SHALL score the requested outcome after each turn.

#### Scenario: User's compound tram refinement
- **WHEN** the frozen tram conversation creates a dashboard, supplies its name and moves current-value cards while requesting door history
- **THEN** the report identifies each trial and turn and verifies the name, requested content and preservation of unrelated widget identities and data

#### Scenario: Regression grader is challenged
- **WHEN** a candidate has a valid schema but omits a requested change or modifies an unrelated binding
- **THEN** the grader fails that outcome without depending on the assistant's success wording

### Requirement: Honest repeated quality reporting
The evaluation harness SHALL distinguish assertions, trials and whole conversations, report repeatability and unavailable dependencies, and SHALL NOT treat mocks or missing provider calls as live model acceptance.

#### Scenario: One response passes many assertions
- **WHEN** one real model response passes21 assertions
- **THEN** the report records one successful trial and21 passed assertions rather than21 successful independent model runs

#### Scenario: Provider cannot be reached
- **WHEN** a required live provider is unavailable
- **THEN** the run cannot pass the live quality gate and reports availability separately from semantic correctness

### Requirement: Isolated evaluation evidence
Evaluation SHALL use ordinary authenticated fixture setup and recoverable cleanup, preserve existing user resources, and exclude credentials and private reasoning from committed fixtures and reports.

#### Scenario: Execute reviewed fixture
- **WHEN** the isolated execution job confirms a prepared dashboard
- **THEN** the created private draft matches the exact reviewed definition and cleanup archives only the job's own resource

### Requirement: Independent durable dashboard task state
Dashboard working facts and pending user turns SHALL be persisted independently of successful preparation and immutable execution reviews, with bounded versioned state scoped to the verified actor and account.

#### Scenario: Name survives incompatible preparation
- **WHEN** the user explicitly names a dashboard and another part of preparation fails
- **THEN** the confirmed name and original request survive restart and the next attempt reuses them without asking for the name again

#### Scenario: Provider fails before a review exists
- **WHEN** a persisted initial turn cannot obtain a provider result
- **THEN** owner recovery exposes the pending task and honest failure without inventing a ready review or discarding the user message

### Requirement: Revisioned idempotent dashboard turns
New-protocol continuations SHALL use scoped request identities and state revisions so duplicate delivery and concurrent requests cannot silently overwrite task facts or accepted reviews.

#### Scenario: Duplicate clarification response is lost
- **WHEN** the same request identity and payload is retried after the first response is lost
- **THEN** the server returns the same persisted turn outcome and does not launch another planning sequence

#### Scenario: Conflicting concurrent updates
- **WHEN** two different turns target the same branch revision
- **THEN** one conflicting writer receives409 and cannot replace the other writer's accepted state

### Requirement: Explicit task branches and pending-work retention
Continuations from restored reviews SHALL use that exact authorized review as their branch base, and pending requests SHALL remain pending until verified or explicitly superseded.

#### Scenario: Undo then continue after reload
- **WHEN** the user selects an earlier review, reloads the task and requests a change from that review
- **THEN** the new branch starts from the selected definition rather than the rejected newer layout

#### Scenario: Empty candidate after a failed request
- **WHEN** a failed move request is followed by an empty model candidate
- **THEN** the move remains pending and the last accepted preview remains available

### Requirement: Compatible owner-scoped state recovery
Legacy immutable reviews SHALL remain readable and executable under existing validation; new task recovery SHALL renew authorization and SHALL NOT expose raw context or grant access through stored selections.

#### Scenario: Another account guesses task identity
- **WHEN** a different actor or account requests dashboard task recovery
- **THEN** the task is not disclosed and no provider or domain mutation is invoked

### Requirement: Authorized dashboard data capability resolution
The dashboard application SHALL resolve compatible widgets and views from current authorized device/source metadata and the shared widget descriptor, preserving data-family identity and semantic prerequisites.

#### Scenario: Door field is not a numeric metric
- **WHEN** the selected source records a boolean door field and the user requests its current state and recorded history
- **THEN** the resolver offers the supported source-bound current and history widgets without inventing a registered metric

#### Scenario: Two sources expose the same label
- **WHEN** two authorized sources match the requested field and neither is explicitly selected
- **THEN** the system reports a meaningful source choice instead of selecting the first result

#### Scenario: Numeric energy input lacks required semantics
- **WHEN** a numeric source has no required counter or tariff semantics for the requested energy/cost widget
- **THEN** numeric type compatibility alone does not mark that widget ready

### Requirement: Capability is distinct from observed availability
Capability resolution SHALL distinguish missing/stale samples, incompatible types and unavailable dependencies, and SHALL NOT fabricate values, units or human-readable labels.

#### Scenario: False and zero are observed
- **WHEN** valid source evidence contains false door state or zero speed
- **THEN** they remain actual observations rather than missing values

#### Scenario: Field exists but interval is empty
- **WHEN** a supported field has no observations in the requested interval
- **THEN** the capability remains valid and the normal preview reports no data without an invented reading

#### Scenario: Text field requested as numeric line
- **WHEN** the only matching field is text and a line history is requested
- **THEN** the resolver exposes the incompatibility and compatible list option without converting text into numbers

### Requirement: Source history view compatibility
AI and ordinary editing SHALL share implemented source-history view constraints, preserve recorded gaps and retain the distinction between observed state and inferred events.

#### Scenario: Door history has an unknown interval
- **WHEN** a boolean line history includes missing observations or a long gap
- **THEN** the graph breaks across the unknown interval and does not invent opening/closing transitions

#### Scenario: Access changes after a choice was stored
- **WHEN** a stored data reference is renewed after access revocation or metadata type change
- **THEN** resolution and final preparation revalidate current authority and reject incompatible or foreign data without altering the last valid review

### Requirement: Typed semantic dashboard operation protocol
New-protocol models SHALL describe bounded domain operations over the selected review and authorized data choices; application code SHALL compile them through ordinary dashboard preparation without accepting raw queries, full ordering or definition JSON from the model.

#### Scenario: Compound current-values and history edit
- **WHEN** the user asks to move speed and next stop to the top, add current door state there and show door history as a graph
- **THEN** compilation reuses existing compatible widgets, creates only missing roles and submits a bounded ordinary preparation intent

#### Scenario: Model supplies arbitrary technical fields
- **WHEN** an operation includes raw coordinates, bindings, URLs or query code
- **THEN** validation rejects the candidate and leaves the accepted review unchanged

### Requirement: Stable targeted edit compilation
The compiler SHALL preserve untargeted widget identity, data and settings, derive permitted changes itself and preserve untouched relative order while allowing only necessary declared responsive reflow.

#### Scenario: Move cards above map
- **WHEN** three current-value cards move to the first row
- **THEN** the map and histories retain their identities, bindings, settings and relative order even if their rows shift downward to avoid collisions

#### Scenario: Hide headers only
- **WHEN** the user requests only header visibility changes
- **THEN** widget geometry, source queries, values and unrelated presentation settings remain unchanged

#### Scenario: Repeated role already exists
- **WHEN** a show-current operation uniquely matches an existing current-value widget
- **THEN** the compiler reuses its key instead of creating a duplicate or converting a history widget

### Requirement: Compatible operation rollout and exact restoration
Stored legacy reviews SHALL retain their original execution payload, and restoration SHALL reuse an authorized immutable definition independently of model-generated layouts.

#### Scenario: Restore then edit
- **WHEN** the user restores a prior version and submits a later refinement
- **THEN** restoration returns the exact stored definition and the later operation compiler uses that definition as its base

#### Scenario: Legacy prepared dashboard executes
- **WHEN** a valid unexpired legacy template or composition review is confirmed
- **THEN** existing owning-service authorization, digest and idempotency checks execute its original reviewed definition without a new-model reinterpretation

### Requirement: Semantic operation contract corrections
The opt-in semantic operation protocol SHALL represent keeping the exact selected
review, same-turn widget renaming, authorized cross-device replacement and
distinct history views without requiring raw model definitions.

#### Scenario: Keep selected review exactly
- **WHEN** a user requests no changes to an authorized READY review
- **THEN** exclusive keep_review reuses its exact saved definition and preparation payload after current authorization checks
- **AND** initial creation and mixed edits are rejected while outstanding earlier goals retain their pending dispositions without being applied or superseded

#### Scenario: Name a newly created widget
- **WHEN** rename_widget references an earlier show/add operation by @id
- **THEN** the server renames only that resolved widget and rejects unknown or forward references

#### Scenario: Replace data with another authorized device
- **WHEN** set_data selects compatible data from another current authorized device
- **THEN** preparation includes that device in dashboard scope and preserves unrelated widgets and existing device scope

#### Scenario: Preserve a distinct history view
- **WHEN** show_history requests a different view without an explicit target
- **THEN** it creates or reuses that view without converting a different existing view
- **AND** an explicit existing target can request a compatible view change

#### Scenario: Bound provider arguments
- **WHEN** a provider supports strict structured output
- **THEN** schema enumerations and ranges constrain placement, periods, views and sizes consistently with server validation

#### Scenario: Duplicate legacy catalogues do not exhaust refinement budget
- **WHEN** an opt-in multi-device review is refined using authoritative data choices
- **THEN** unused templates and duplicate legacy field/metric summaries are removed before the unchanged input budget is enforced
- **AND** current data choices, selected widget state and credential scanning remain intact

