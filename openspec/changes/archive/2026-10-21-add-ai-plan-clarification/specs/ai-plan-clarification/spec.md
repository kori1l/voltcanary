## ADDED Requirements

### Requirement: Scoped persistent clarification
The assistant SHALL accept bounded follow-up answers to a current owner's
nonexpired unexecuted plan and prepare a new plan using original intent,
explicit selections and fresh authorized catalogues with the same model.
#### Scenario: Dashboard name answer
- **WHEN** the assistant asks for a dashboard name and the user supplies it
- **THEN** a new review retains the selected equipment and resolves the clarified intent without executing it
#### Scenario: Foreign or expired task
- **WHEN** clarification targets another owner's task or an expired/executed task
- **THEN** it is rejected before any model call or resource mutation

### Requirement: Shared clarification interaction
Both assistant page and drawer SHALL expose a reply form, preserve answers on
failure, prevent concurrent old-plan execution and ignore stale session/context responses.
#### Scenario: Retry after provider failure
- **WHEN** a clarification request fails
- **THEN** the old review and entered answer remain available for retry
#### Scenario: New clarified review
- **WHEN** a reply succeeds
- **THEN** the new task is recoverable and any write still requires explicit review and confirmation
