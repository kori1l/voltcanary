## ADDED Requirements

### Requirement: Deployment policies are safely manageable

Operators SHALL be able to disable or delete ML deployment policies from the
console without corrupting runtime policy resolution.

#### Scenario: Operator disables an active device policy

- **GIVEN** a device-scoped policy is active
- **WHEN** an authorized operator disables the policy with a reason
- **THEN** the policy SHALL no longer be selected for live inference
- **AND** policy resolution SHALL fall back to the next applicable policy or
  baseline behavior
- **AND** the action SHALL be audit logged.

#### Scenario: Operator deletes an inactive policy

- **GIVEN** a deployment policy is inactive or disabled
- **WHEN** an authorized operator deletes it
- **THEN** the policy SHALL be removed from default policy lists
- **AND** historical prediction traceability SHALL remain readable.

#### Scenario: Operator attempts unsafe policy deletion

- **GIVEN** deleting a policy would remove the only active serving path without
  fallback
- **WHEN** the operator requests deletion
- **THEN** the system SHALL require explicit confirmation or block the action
- **AND** return a structured explanation of affected scope.
