## ADDED Requirements

### Requirement: Bounded readable alert triage

Inbox SHALL show sortable severity/time/status rows with device, rule, age and reachable actions, retain metric/time-window deep links and explain unavailable context. Errors SHALL not imply no active alerts.

#### Scenario: An alert lacks accessible registry or rule context

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator reviews Inbox or Details
- **THEN** the missing context is explained, canonical identifiers remain inspectable and the row/actions stay within a bounded table rather than widening the document.

### Requirement: Reviewed alert lifecycle actions

Supported lifecycle mutations SHALL use named controls and one reviewed dialog with the affected alert, action, reason and result feedback while preserving audit requirements and authorization. Assignee state SHALL remain inspectable; when the server exposes no assignment mutation endpoint, the console SHALL explicitly explain that assignment changes are unavailable and SHALL not imply a successful assignment.

#### Scenario: Resolution is requested

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator cancels or submits the reviewed reason
- **THEN** cancellation causes no mutation and a submitted result refreshes the alert state without losing its evidence context.

### Requirement: Condition-aware rule editing and testing

Rule creation/editing SHALL use permanent labels, supported metric selection, units, condition-specific parameters, readable if/then text and named scope/channel selection. Test SHALL have an explicit interval and a result separate from Create.

#### Scenario: An operator changes the rule condition

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** threshold, multiplier or offline criteria are selected
- **THEN** only relevant parameters are required, invalid fields are identified and testing does not silently create or enable a rule.

### Requirement: Channel-specific readable notification setup

Webhook, Email and Telegram editors SHALL group identity, recipients and advanced parameters with permanent labels and credential-reference guidance. Creation, availability, enable state, diagnostic status and test delivery SHALL be distinct operations using typed APIs without exposing provider secrets.

#### Scenario: An operator selects Email or Telegram

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the channel is configured and tested
- **THEN** the relevant fields and optional controls are clear, saved secret state is explained without disclosure, and test results reflect actual delivery attempt status.

### Requirement: Understandable routing and quiet hours

Preferences SHALL group severities, channel types and quiet hours with normal-sized labeled time controls, timezone selection, critical-bypass explanation and a routing summary beside Save.

#### Scenario: A selected channel type has no usable configured channel

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** routing is reviewed or saved
- **THEN** its availability is explained and the quiet-hours timezone and resulting delivery choices are visible without silently dropping existing preferences.
