## ADDED Requirements

### Requirement: User-scoped AI context

AI creation tasks SHALL derive identity and account scope server-side and SHALL send only bounded currently authorized context to the selected user's provider.

#### Scenario: Foreign identifiers in context

- **WHEN** user A submits B's resource identifiers, provider identifiers or task refs
- **THEN** no protected B data enters the model request, task response, cache or execution result.

### Requirement: Owning-service authorization

AI SHALL invoke domain user APIs with the current user's verified authorization and SHALL NOT substitute shared service credentials or implement a second ownership policy.

#### Scenario: Access changes after planning

- **WHEN** an account becomes unavailable or a referenced resource is no longer authorized before execute
- **THEN** the owning service rejects the operation and AI reports the denial without alternate elevated execution.

### Requirement: Versioned durable execution

Plans SHALL persist owner/account/version, confirmation and step results and SHALL prevent concurrent duplicate execution across replicas.

#### Scenario: Restart and repeated execute

- **WHEN** a confirmed task is repeated or resumed after restart
- **THEN** the server returns or reconciles the original outcome using the same action idempotency identity without creating a duplicate.

#### Scenario: Changed reviewed payload

- **WHEN** the task payload changes or expires after review
- **THEN** old confirmation cannot execute the new payload.

### Requirement: Explicit uncertain and partial outcomes

Execution SHALL distinguish failed, partially completed and unknown outcomes without assuming an unreceived response means no mutation occurred.

#### Scenario: Response lost after commit

- **WHEN** an owning service commits a creation but its response is lost
- **THEN** execution reconciles through the owning service's idempotency contract and does not retry under a new identity.

### Requirement: Bounded typed intent handling

New creation workflows SHALL accept only validated intent schemas and SHALL request clarification or report unsupported input instead of inventing executable parameters.

#### Scenario: Invalid output or missing selection

- **WHEN** the model returns invalid fields or a required choice is ambiguous after the bounded repair attempt
- **THEN** the task remains non-executable and the user receives a specific clarification or unsupported result.
