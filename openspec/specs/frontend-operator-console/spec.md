# frontend-operator-console Specification

## Purpose
Define frontend operator-console structure and shared page behavior for data
loading, draft persistence, polling, confirmations, and service-backed
workflows.
## Requirements
### Requirement: Pages Compose Focused Workflow Modules

Frontend route pages SHALL act as route shells that compose focused workflow
hooks, model helpers, and presentational components.

#### Scenario: Device details gains workflow behavior

- **GIVEN** the device details route already includes telemetry, analytics,
  alerts, maintenance, registry, and simulation workflows
- **WHEN** new behavior is added to one of those workflows
- **THEN** the behavior SHALL be added to a focused feature hook or component
  before the route page grows further
- **AND** the route page SHALL keep route params, tab selection, and high-level
  composition responsibilities.

#### Scenario: ML Settings gains lifecycle behavior

- **GIVEN** ML Settings already includes settings, datasets, labels, training,
  models, policies, and monitoring workflows
- **WHEN** a lifecycle behavior changes
- **THEN** data loading, draft persistence, confirmation prompts, polling, and
  business validation SHALL be implemented in focused hooks or model helpers
- **AND** tab components SHALL receive explicit data and command props.

### Requirement: Browser Storage Is Accessed Through Typed Hooks

Frontend localStorage/sessionStorage use SHALL be wrapped in typed helpers or
hooks for operator workflow state.

#### Scenario: A draft or chart preference is persisted

- **WHEN** a page needs to persist form drafts, selected metrics, chart density,
  source ids, or async job status
- **THEN** storage read/write behavior SHALL live behind a typed helper or hook
- **AND** parse failures SHALL fall back to safe defaults without breaking page
  rendering.

### Requirement: Large Page Touches Include Decomposition

Large operator pages SHALL not receive additional independent workflow behavior
without extraction.

#### Scenario: A change touches a large page

- **GIVEN** a page is over the agreed reviewability threshold or owns multiple
  independent workflows
- **WHEN** a feature change touches that page
- **THEN** the change SHALL extract the affected workflow into feature modules
  or record an explicit OpenSpec task explaining why extraction is deferred
- **AND** tests SHALL cover the extracted helper, hook, or component boundary.

### Requirement: Notification Settings Use Focused Feature Modules

Notification channel and preference settings SHALL remain user-facing while
route pages delegate notification-specific behavior to focused frontend
modules.

#### Scenario: Notification settings UI changes

- **WHEN** account notification settings are touched
- **THEN** route pages SHALL compose focused notification components, hooks, or
  model helpers
- **AND** API access SHALL remain centralized in typed alert notification
  helpers rather than inline fetch calls.

### Requirement: Notification Settings Configure Email And Telegram Channels

The operator console SHALL expose email and Telegram notification configuration
through focused notification settings UI.

#### Scenario: Email notification channel is configured

- **GIVEN** an operator is on the notification settings surface
- **WHEN** the operator selects `EMAIL` as the channel type
- **THEN** the UI SHALL show email-specific controls for recipients, optional
  cc/bcc, from alias, and subject prefix
- **AND** the submitted payload SHALL use the typed alerts API client
- **AND** the submitted channel config SHALL exclude provider secrets.

#### Scenario: Telegram notification channel is configured

- **GIVEN** an operator is on the notification settings surface
- **WHEN** the operator selects `TELEGRAM` as the channel type
- **THEN** the UI SHALL show Telegram-specific controls for chat id, optional
  parse mode, and web preview behavior
- **AND** the submitted payload SHALL use the typed alerts API client
- **AND** the submitted channel config SHALL exclude provider secrets.

#### Scenario: Notification preferences are configured

- **GIVEN** an operator is on the notification settings surface
- **WHEN** the operator updates delivery preferences
- **THEN** the UI SHALL expose enabled state, severity filters, channel type
  filters, device scope, quiet hours, timezone, and critical quiet-hours bypass
- **AND** the UI SHALL persist those preferences through the existing alerts
  notification preferences API.

### Requirement: Notification Settings Surface Delivery Operations

The operator console SHALL expose notification test delivery and diagnostics
through focused notification settings modules.

#### Scenario: Operator runs test delivery

- **GIVEN** an operator is viewing an existing notification channel
- **WHEN** the operator triggers a test delivery
- **THEN** the frontend SHALL call the typed alerts notification API helper
- **AND** it SHALL show the resulting attempt status, retry state, or failure
  summary without asking for provider secrets.

#### Scenario: Operator reviews channel diagnostics

- **GIVEN** an operator is viewing notification settings
- **WHEN** channel diagnostics are loaded
- **THEN** the frontend SHALL show enabled state, adapter availability, last
  success or failure, retrying/failed/pending counts, and last test status
- **AND** route pages SHALL delegate this behavior to focused notification
  components, hooks, or model helpers.

