## ADDED Requirements

### Requirement: Durable complete assistant conversations
The system SHALL persist owner-scoped ordered submitted messages and assistant
outcomes with conversation, request, task, review and execution references. History
SHALL survive reload and service restart independently of React component lifetime.

#### Scenario: Restore successful and failed conversations
- **WHEN** the user reopens the assistant after reload or service restart
- **THEN** saved messages and actual outcomes are restored in order
- **AND** restoration invokes neither the model nor domain execution.

#### Scenario: Lost response and concurrent clients
- **WHEN** a submitted request is retried with its original requestId or another tab submits an outdated revision
- **THEN** the system deduplicates the request or returns a revision conflict without losing messages or duplicating execution.

#### Scenario: Account and expired resource boundaries
- **WHEN** identity changes or a linked review expires
- **THEN** another owner's chat is inaccessible and expired execution cannot be confirmed
- **AND** retained conversation text and truthful resource availability remain distinct.

#### Scenario: Safe persistence and unsent drafts
- **WHEN** a message contains credentials or a local draft is restored
- **THEN** credentials are redacted before persistence and draft state is scoped to its owner
- **AND** unsent text is not presented as a submitted message.
