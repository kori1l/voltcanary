# Alert Notifications Specification

## ADDED Requirements

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
