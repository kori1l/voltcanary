## ADDED Requirements

### Requirement: Committed commands survive read refresh failure

A confirmed mutation result SHALL remain successful when its subsequent read refresh fails, while a transport failure before confirmation SHALL remain outcome-unknown and require reconciliation before a non-idempotent retry.

#### Scenario: Channel added but list reload fails

- **GIVEN** create returned a canonical channel ID
- **WHEN** the channel list refresh fails
- **THEN** the UI states channel added and list unavailable, with a read retry instead of another create.

#### Scenario: Create response lost

- **GIVEN** a non-idempotent create was sent
- **WHEN** transport failed before an ID was received
- **THEN** the UI explains uncertainty and reconciles existing records rather than claiming failure guarantees no record.

### Requirement: Entity command pending and understandable feedback

Operational row commands SHALL reject duplicate concurrent submissions per entity and action, preserve independent row access, and expose sanitized errors and clipboard results through meaningful feedback.

#### Scenario: Double acknowledge

- **GIVEN** an OPEN alert is visible
- **WHEN** Ack is clicked twice before completion
- **THEN** one acknowledge request occurs and another alert remains operable.

#### Scenario: Clipboard fails

- **GIVEN** a technical ID can be copied
- **WHEN** clipboard access is rejected
- **THEN** the UI announces copy failure and offers manual selection without an unhandled promise.
