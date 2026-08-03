## ADDED Requirements

### Requirement: Failure prediction production readiness is auditable

The system SHALL maintain an operator-readable audit status showing which
failure-prediction production blockers are implemented, which are partially
implemented, and which remain open.

#### Scenario: Engineer reviews production readiness

- **GIVEN** the ML failure prediction production audit exists
- **WHEN** an engineer reviews the current implementation state
- **THEN** the audit status SHALL map each major blocker to implemented evidence
  or remaining work
- **AND** implemented evidence SHALL reference archived OpenSpec changes,
  relevant tests, or concrete runtime verification.

### Requirement: Failure prediction lifecycle has repeatable production smoke coverage

The system SHALL provide a repeatable verification path for the complete
failure-prediction lifecycle in the local Docker environment.

#### Scenario: Data engineer verifies a failure model lifecycle

- **GIVEN** the Docker application profile is running
- **WHEN** the verification scenario creates or imports training data, generates
  features and horizon labels, trains a failure model, reviews approval gates,
  deploys the model, and recalculates live prediction
- **THEN** the scenario SHALL prove that live inference used or explicitly
  rejected the selected `modelVersionId`
- **AND** persisted traceability SHALL include model id, feature hash, input
  hash, policy source, fallback reason when applicable, and failure-risk fields
- **AND** monitoring signals SHALL expose fallback, error, canary or shadow, and
  quality outcomes when labels exist.

### Requirement: ML Settings supports production operator workflows

The ML Settings frontend SHALL present ML lifecycle controls in stable,
responsive layouts with readable errors.

#### Scenario: Operator uses ML Settings workflow tabs

- **GIVEN** the operator opens dataset, label, training, model, policy, or
  monitoring tabs
- **WHEN** forms contain multiple controls, long identifiers, or backend errors
- **THEN** controls SHALL remain aligned without label/input overlap
- **AND** long identifiers SHALL wrap or truncate within their containers
- **AND** API failures SHALL be shown as concise operator-readable messages
  rather than raw JSON response bodies.
