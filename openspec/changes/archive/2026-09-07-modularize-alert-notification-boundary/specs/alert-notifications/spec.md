# Alert Notifications Specification

## ADDED Requirements

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
