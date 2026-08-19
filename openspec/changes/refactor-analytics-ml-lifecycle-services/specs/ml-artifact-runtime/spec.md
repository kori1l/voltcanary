# ml-artifact-runtime Specification

## ADDED Requirements

### Requirement: Model Registry Commands Are Separate From Policy Resolution

Analytics-service model registry behavior SHALL keep model lifecycle commands,
approval gate verification, deployment policy mutation, policy resolution, and
DTO mapping in separate components.

#### Scenario: Operator approves a candidate model

- **WHEN** approval is requested
- **THEN** artifact metadata and approval gate validation SHALL run through an
  explicit verifier
- **AND** deployment policy resolution SHALL not be coupled to the approval
  command implementation.

#### Scenario: Runtime resolves a deployment policy

- **WHEN** analytics recalculation resolves the active model for a device
- **THEN** resolution order, canary inclusion, shadow/active choice, and
  fallback behavior SHALL be owned by a policy resolver
- **AND** model list or DTO mapping code SHALL not affect runtime selection.
