# alert-notifications Specification

## Purpose
Define the alert-service notification module contract: how alert lifecycle
events request notification delivery, how channels and preferences create
durable attempts, how provider adapters deliver messages, and how retry,
idempotency, lease, retention, and frontend configuration behavior remain
owned by the notification boundary.
## Requirements
### Requirement: Alert Notifications Stay Modular Inside Alert Service

Alert-service SHALL keep notification delivery as an internal module with an
explicit boundary from alert lifecycle code.

#### Scenario: Alert lifecycle requests notification delivery

- **GIVEN** alert-service has created or escalated an alert
- **WHEN** alert lifecycle code requests notification delivery
- **THEN** it SHALL call an alert notification port with an immutable
  notification request snapshot
- **AND** it SHALL NOT pass JPA alert entities as the boundary contract
- **AND** it SHALL NOT depend on provider-specific delivery implementations.

#### Scenario: Notification module handles alert notification request

- **WHEN** the notification module receives an alert notification request
- **THEN** it SHALL evaluate notification preferences and enabled channels
- **AND** create durable, idempotent notification attempts for eligible channels
- **AND** schedule provider delivery after the alert transaction commits.

### Requirement: Notification Provider Adapters Are Isolated

Notification provider integrations SHALL be implemented behind provider adapter
interfaces selected by channel type.

#### Scenario: Worker delivers a notification attempt

- **GIVEN** a due notification attempt exists for a supported channel type
- **WHEN** the notification delivery worker processes the attempt
- **THEN** it SHALL select the provider adapter by channel type
- **AND** pass a normalized delivery command containing the attempt,
  notification message, channel configuration, and source alert snapshot
- **AND** the adapter SHALL return a normalized delivery result.

#### Scenario: Provider adapter reports delivery result

- **WHEN** a provider adapter completes or fails delivery
- **THEN** retry decisions and attempt state transitions SHALL remain in the
  notification application layer
- **AND** provider-specific response details SHALL be reduced to a bounded
  response summary, provider message id when available, error code, and
  retryable marker.

### Requirement: Public Notification Routes Remain Compatible

The modularization SHALL preserve the current public notification API shape
while the module remains inside alert-service.

#### Scenario: Operator manages notification settings

- **WHEN** the frontend calls notification channel, preference, or attempt routes
  under `/alerts`
- **THEN** alert-service SHALL continue to serve the existing routes and response
  shapes
- **AND** the implementation SHALL route those calls into the notification
  module rather than alert lifecycle services.

#### Scenario: Frontend edits notification settings

- **GIVEN** an operator is configuring account notification behavior
- **WHEN** the operator opens the notification settings UI
- **THEN** the frontend SHALL allow channel and preference configuration through
  typed alert notification API helpers
- **AND** route pages SHALL delegate notification settings behavior to focused
  notification components, hooks, or model helpers when they are touched.

### Requirement: Notification Boundary Remains Extractable

The notification module SHALL avoid assumptions that prevent future extraction
into a separate service.

#### Scenario: Future extraction is considered

- **WHEN** notification delivery is moved to an internal HTTP service or Kafka
  request topic
- **THEN** alert lifecycle code SHALL be replaceable at the alert notification
  port implementation
- **AND** provider adapter, preference, channel, attempt, idempotency, retry,
  and retention behavior SHALL remain portable as notification-owned logic.

### Requirement: Email Notification Adapter Delivers Alert Messages

The notification module SHALL support email as a provider adapter for alert
notification attempts.

#### Scenario: Email channel config is valid

- **GIVEN** an enabled `EMAIL` notification channel exists
- **WHEN** its config contains at least one valid recipient and no secret-like
  values
- **THEN** the notification module SHALL accept the channel configuration
- **AND** credentials SHALL be resolved from `credentialsRef` or service-level
  mail configuration rather than channel config JSON.

#### Scenario: Email notification attempt is delivered

- **GIVEN** a due notification attempt targets an enabled `EMAIL` channel
- **WHEN** the email provider accepts or sends the message
- **THEN** the attempt SHALL be marked `SENT`
- **AND** the attempt SHALL store a bounded provider response summary and
  provider message id when available.

#### Scenario: Email provider fails delivery

- **GIVEN** a due notification attempt targets an enabled `EMAIL` channel
- **WHEN** the email provider returns a transient SMTP or network error
- **THEN** the notification module SHALL mark the attempt `RETRYING` while retry
  attempts remain
- **AND** it SHALL mark the attempt `FAILED` when the failure is permanent or
  retry attempts are exhausted.

### Requirement: Telegram Notification Adapter Delivers Alert Messages

The notification module SHALL support Telegram as a provider adapter for alert
notification attempts.

#### Scenario: Telegram channel config is valid

- **GIVEN** an enabled `TELEGRAM` notification channel exists
- **WHEN** its config contains a valid `chatId`, optional supported parse mode,
  optional preview flag, and no secret-like values
- **THEN** the notification module SHALL accept the channel configuration
- **AND** bot credentials SHALL be resolved from `credentialsRef` or service-level
  Telegram configuration rather than channel config JSON.

#### Scenario: Telegram notification attempt is delivered

- **GIVEN** a due notification attempt targets an enabled `TELEGRAM` channel
- **WHEN** Telegram accepts the message
- **THEN** the attempt SHALL be marked `SENT`
- **AND** the attempt SHALL store a bounded provider response summary and
  Telegram message id when available.

#### Scenario: Telegram provider fails delivery

- **GIVEN** a due notification attempt targets an enabled `TELEGRAM` channel
- **WHEN** Telegram returns `429` or a `5xx` provider response
- **THEN** the notification module SHALL mark the attempt `RETRYING` while retry
  attempts remain
- **AND** it SHALL mark the attempt `FAILED` for permanent provider responses
  such as invalid chat, invalid token, unauthorized bot, or invalid config.

### Requirement: Provider Adapters Reuse Canonical Notification Messages

Email and Telegram adapters SHALL use the notification module's canonical alert
message and may only apply provider-specific formatting.

#### Scenario: Provider-specific message is built

- **GIVEN** an email or Telegram adapter is delivering an alert notification
- **WHEN** the provider payload is created
- **THEN** alert severity, alert type, title, description, source context, and
  alert link SHALL come from the canonical notification message
- **AND** provider adapters SHALL only add formatting or escaping required by
  the target provider.

### Requirement: Users Configure Notification Channels And Preferences In The Frontend

Email and Telegram notification behavior SHALL be configurable through the
operator frontend without requiring users to edit raw backend data.

#### Scenario: User configures an email channel

- **GIVEN** an operator has access to account notification settings
- **WHEN** the operator creates or updates an `EMAIL` channel
- **THEN** the frontend SHALL provide controls for channel name, enabled state,
  recipients, optional cc/bcc, from alias, subject prefix, and credentials
  reference when needed
- **AND** the frontend SHALL submit only non-secret channel config to the alerts
  notification API.

#### Scenario: User configures a Telegram channel

- **GIVEN** an operator has access to account notification settings
- **WHEN** the operator creates or updates a `TELEGRAM` channel
- **THEN** the frontend SHALL provide controls for channel name, enabled state,
  chat id, optional parse mode, web preview toggle, and credentials reference
  when needed
- **AND** the frontend SHALL submit only non-secret channel config to the alerts
  notification API.

#### Scenario: User edits notification preferences

- **GIVEN** account notification channels exist
- **WHEN** the operator edits notification preferences
- **THEN** the frontend SHALL provide controls for notification enabled state,
  severities, channel type filters, device scope, quiet hours, timezone, and
  critical quiet-hours bypass
- **AND** those preferences SHALL apply before email or Telegram delivery
  attempts are sent.

### Requirement: Notification Channels Support Auditable Test Delivery

The notification module SHALL let an authorized operator send a test delivery
through an existing notification channel without creating or mutating alert
lifecycle records.

#### Scenario: Operator tests a notification channel

- **GIVEN** an enabled notification channel belongs to the caller's account
- **WHEN** the operator requests a test delivery for that channel
- **THEN** the notification module SHALL create an auditable notification attempt
  with a test delivery event type
- **AND** it SHALL deliver the test through the provider adapter registry using
  the canonical notification message path
- **AND** it SHALL NOT create a new alert or write alert lifecycle events.

#### Scenario: Test delivery request contains secret-like fields

- **GIVEN** an operator requests a test delivery
- **WHEN** the request body contains a key that looks like a token, password,
  credential, API key, or secret
- **THEN** the notification module SHALL reject the request
- **AND** it SHALL NOT persist a notification attempt.

### Requirement: Notification Retry Policy Is Bounded And Explainable

The notification application layer SHALL own retry scheduling with configurable
bounded backoff and operator-visible retry state.

#### Scenario: Retryable provider failure is scheduled

- **GIVEN** a provider adapter returns a retryable delivery failure
- **WHEN** retry attempts remain
- **THEN** the notification module SHALL mark the attempt `RETRYING`
- **AND** it SHALL set a bounded next attempt timestamp according to retry
  policy
- **AND** the response or diagnostics surface SHALL expose retry count, maximum
  attempts, next attempt timestamp, last error code, and redacted provider
  summary.

#### Scenario: Retry attempts are exhausted

- **GIVEN** a notification attempt has reached the configured maximum attempts
- **WHEN** the latest provider result is retryable
- **THEN** the notification module SHALL mark the attempt `FAILED`
- **AND** it SHALL expose that retry attempts are exhausted
- **AND** it SHALL preserve the final normalized error code and redacted
  provider summary.

### Requirement: Notification Channels Expose Read-Only Diagnostics

The notification module SHALL expose per-channel diagnostics that summarize
delivery health without mutating delivery state or revealing secrets.

#### Scenario: Operator views channel diagnostics

- **GIVEN** a notification channel belongs to the caller's account
- **WHEN** the operator requests channel diagnostics
- **THEN** the response SHALL include channel id, channel type, enabled state,
  adapter registration status, last success, last failure, retrying count,
  failed count, pending count, oldest pending or retrying age, and last test
  attempt status when available
- **AND** all provider details SHALL be bounded and redacted.

#### Scenario: Diagnostics route cannot access another account

- **GIVEN** a notification channel belongs to another account
- **WHEN** the operator requests diagnostics for that channel
- **THEN** the notification module SHALL return the same not-found behavior used
  by existing channel management routes.

