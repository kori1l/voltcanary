## MODIFIED Requirements

### Requirement: Shared clarification interaction
The assistant route and global launcher SHALL open the same application-level conversation with one composer for new requests and replies. The conversation SHALL preserve answers on failure, prevent old-plan execution during refinement, ignore stale session responses and retain history and input across presentation changes.

#### Scenario: Retry after provider failure
- **WHEN** a clarification request fails
- **THEN** the old review and entered answer remain available for retry

#### Scenario: New clarified review
- **WHEN** a reply succeeds
- **THEN** the new task is recoverable and any write still requires explicit review and confirmation

## ADDED Requirements

### Requirement: Configurable review and clarification compatibility

Pending configured-resource UI work SHALL preserve exact questions, authorized schema/source choices and owner review versions under the VCL, strict import and adapter contracts, without exposing secrets or asking users to repair technical schemas.

#### Scenario: External source requires a choice
- **WHEN** several authorized customer fields or datasets match a requested change
- **THEN** the shared assistant asks a concrete scoped question and preserves the accepted review until a new candidate is valid
