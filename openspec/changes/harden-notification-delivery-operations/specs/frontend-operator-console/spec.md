# frontend-operator-console Specification

## ADDED Requirements

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
