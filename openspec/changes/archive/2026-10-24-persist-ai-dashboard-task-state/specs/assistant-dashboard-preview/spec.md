## ADDED Requirements

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
