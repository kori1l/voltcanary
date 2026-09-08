# Alert Notifications Specification

## ADDED Requirements

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
