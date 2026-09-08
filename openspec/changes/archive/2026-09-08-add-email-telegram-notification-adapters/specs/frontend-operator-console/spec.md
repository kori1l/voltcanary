# Frontend Operator Console Specification

## ADDED Requirements

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

