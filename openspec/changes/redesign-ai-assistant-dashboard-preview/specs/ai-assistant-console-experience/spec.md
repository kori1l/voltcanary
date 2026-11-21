## MODIFIED Requirements

### Requirement: Contextual assistant requests
The application-level assistant SHALL accept ordinary requests in an independent light window and show explicitly attached device context using readable names. Opening or repositioning the window SHALL NOT implicitly select the device, alert or interval on the underlying page. Technical context and diagnostics SHALL remain secondary.

#### Scenario: An operator opens Assistant from telemetry or an alert
- **WHEN** the operator opens the assistant over a device or alert page
- **THEN** the window retains its conversation and only an explicit context attachment or selected-device handoff changes its target

### Requirement: Clear plan and execution review
Plans SHALL prioritize readable answers, affected objects, proposed changes and consequences. Read-only operations MAY execute directly after preparation. Mutating actions SHALL require explicit reviewed confirmation; the creation button beside an exact dashboard preview SHALL constitute that confirmation without a duplicate modal.

#### Scenario: A write plan is returned
- **WHEN** the operator reviews, refines or confirms a dashboard preview
- **THEN** no write occurs on review or refinement and confirmation executes only the displayed immutable task version

### Requirement: Task-scoped assistant context
Assistant context SHALL contain only explicit bounded selections and approved task handoffs, without credentials or implied resource authorization. A conversation SHALL retain its selected device when the underlying route changes.

#### Scenario: ML and maintenance request
- **WHEN** an operator opens the assistant over a dataset or maintenance operation
- **THEN** the existing conversation remains independent and no dataset or operation is added implicitly

## ADDED Requirements

### Requirement: Timed clarification defaults
Unanswered assistant questions SHALL show a proposed default with60 seconds to
start answering. Typing SHALL cancel automatic selection for that question, even
after erasing the draft. Automatic choices SHALL be labelled and use ordinary
validated model-proposed concrete values, not arbitrary prose or another question.
The frontend SHALL NOT infer missing fields by matching question wording.
Remembered task values SHALL persist server-side across question-only turns and recovery.
Automatic choices SHALL use ordinary
validated planning; they SHALL NOT confirm mutations, repeat defaults for the
same field, or loop after provider failure. Referential name replies SHALL retain the previous
reviewed name. Expanded thinking status SHALL align with the composer width.

#### Scenario: An operator leaves a name question unanswered
- **WHEN** sixty seconds pass without typing or opting out
- **THEN** the assistant submits its displayed default name for a new review, with creation still requiring explicit confirmation

#### Scenario: An operator starts typing near the deadline
- **WHEN** typing begins before the deadline and the draft is subsequently cleared
- **THEN** no automatic answer is sent for that question

### Requirement: Honest current vehicle readings
Vehicle maps in dashboard previews and ordinary vehicle views SHALL NOT present
stale speed as current. Fleet views SHALL distinguish owner-reported freshness
from registration status and retain a full timestamp for the last measurement.

#### Scenario: An offline vehicle retains its last position
- **WHEN** the telemetry owner marks the last recorded position STALE
- **THEN** the position can remain visible, current speed is unavailable, and its old numeric speed is not presented as a current reading

### Requirement: Visible model waiting within server execution limits
The overlay SHALL show pending-model status and elapsed time while the server
reports the turn pending, including after 60 and 90 seconds if still pending.
Minimize and reopen SHALL preserve the same in-flight request. The server SHALL
enforce the turn and plan deadlines from the graph runtime. Status SHALL NOT
invent server stages or expose internal reasoning.

#### Scenario: A slow model prepares a dashboard
- **WHEN** a provider takes longer than one minute and the turn is still within its deadline
- **THEN** the request continues with a visible elapsed wait and the previous review remains unavailable for confirmation until refinement finishes

#### Scenario: The server deadline expires
- **WHEN** the server reports a terminal timeout or an uncertain outcome
- **THEN** the overlay shows the server-reported outcome, preserves the conversation and offers only the recovery actions allowed by that outcome

### Requirement: Configurable review and clarification compatibility

Pending configured-resource UI work SHALL preserve exact questions, authorized schema/source choices and owner review versions under the VCL, strict import and adapter contracts, without exposing secrets or asking users to repair technical schemas.

#### Scenario: External source requires a choice
- **WHEN** several authorized customer fields or datasets match a requested change
- **THEN** the shared assistant asks a concrete scoped question and preserves the accepted review until a new candidate is valid
