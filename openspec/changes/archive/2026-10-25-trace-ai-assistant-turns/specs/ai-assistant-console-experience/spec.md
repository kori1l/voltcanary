## ADDED Requirements

### Requirement: Persistent observable assistant execution trace
The system SHALL retain bounded owner-scoped observable step events correlated with
conversation turns and actual provider/tool attempts, including failures before a
review exists. Provider response, review acceptance and execution success SHALL be distinct.

#### Scenario: Diagnose a rejected candidate
- **WHEN** a model candidate is rejected and repaired
- **THEN** inspection shows each safe candidate, rejected fields/check codes, capability snapshot and actual attempt outcome in order.

#### Scenario: Safe export and access
- **WHEN** an owner exports a conversation trace
- **THEN** the export contains correlated observable steps without credentials or private reasoning
- **AND** another account cannot retrieve the trace.

#### Scenario: Interrupted or bounded capture
- **WHEN** the process restarts during a step or a payload exceeds its limit
- **THEN** the trace identifies interruption or truncation truthfully without implying success or re-executing a domain command.
