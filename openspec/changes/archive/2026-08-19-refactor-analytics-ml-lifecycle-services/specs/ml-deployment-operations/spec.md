# ml-deployment-operations Specification

## ADDED Requirements

### Requirement: Deployment Policy Logic Is Isolated

Deployment policy mutation and runtime policy resolution SHALL be isolated from
generic model registry listing and lifecycle code.

#### Scenario: Operator updates a deployment policy

- **WHEN** a policy is created, disabled, deleted, or rolled back
- **THEN** the command SHALL run through a policy-focused service
- **AND** the action SHALL emit audit data without depending on model response
  mapping internals.

#### Scenario: Analytics evaluates active and shadow policies

- **WHEN** analytics-service selects active, canary, shadow, or fallback model
  behavior
- **THEN** runtime selection SHALL use a resolver with deterministic ordering
- **AND** the resolver SHALL be testable without invoking model lifecycle
  commands.
