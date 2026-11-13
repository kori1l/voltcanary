# ai-assistant-console-experience Specification

## Purpose
Define contextual assistant requests, plan/execution review, capability-aware provider/model setup and safe credential rotation.
## Requirements
### Requirement: Contextual assistant requests

The page and drawer assistant SHALL show current device/alert/interval context and allow ordinary requests without editing JSON. Raw context and internal safety diagnostics SHALL remain secondary advanced detail.

#### Scenario: An operator opens Assistant from telemetry or an alert

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the request is reviewed
- **THEN** the visible context matches the canonical route entities and interval and changing context does not silently target an unrelated device.

### Requirement: Clear plan and execution review

Plans SHALL prioritize named affected objects, proposed changes and consequences with separate read-only and mutating execution paths. Mutating actions SHALL require the existing confirmation review; bypass-style commands SHALL not be an ordinary option.

#### Scenario: A write plan is returned

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator reviews, cancels or confirms it
- **THEN** no write occurs on review/cancel, effects are understandable before confirmation and execution status/errors are tied to individual actions.

### Requirement: Capability-aware provider and model setup

Provider setup SHALL separate connection, model selection and advanced tuning, use capability-supported fields, label timeouts with understandable units and show real connection-test results.

#### Scenario: A provider with different supported features is chosen

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator configures a model
- **THEN** unsupported options are explained or disabled and canonical model/capability values are submitted without pretending a connection test succeeded.

### Requirement: Safe understandable secret rotation

Provider secret state and rotation SHALL use separate reviewed controls, indicate configured/missing status without disclosure and preserve existing encryption and authorization boundaries. Secrets SHALL not enter persisted drafts, request context or diagnostic output.

#### Scenario: A configured provider key is rotated

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator reviews and applies the replacement
- **THEN** the affected provider and connection impact are clear and subsequent UI contains only configured-state metadata.

### Requirement: Task-scoped assistant context

Assistant requests SHALL summarize allowlisted current task entities, intervals and filters using readable names, without conveying credentials or granting resource authorization.

#### Scenario: ML and maintenance request

- **GIVEN** a selected dataset or operation is visible
- **WHEN** the operator opens the assistant
- **THEN** the preview and payload retain that selection and time window rather than generic Console context.

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

### Requirement: Stage-four context and diagnostic preservation
The assistant SHALL preserve pending dashboard task intent after preparation failure,
constrain strict semantic data references to the current authorized capability
projection, and retain safe failure evidence for recorded scenario replays.

#### Scenario: A name answer follows a failed dashboard preparation
- **WHEN** dashboard preparation fails and the next user message supplies a name
- **THEN** the answer continues the dashboard task rather than proposing device creation.

#### Scenario: Strict response generation uses current data choices
- **WHEN** the semantic compiler requests a strict model response
- **THEN** data references are constrained to the current offered choices
- **AND** initial composition does not offer operations requiring an existing review.

#### Scenario: Provider output is incomplete
- **WHEN** a provider response reports incomplete output
- **THEN** safe response status and available usage counters are retained with the turn trace
- **AND** no private reasoning or raw provider body is persisted.

