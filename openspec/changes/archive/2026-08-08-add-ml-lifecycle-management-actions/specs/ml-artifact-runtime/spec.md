## ADDED Requirements

### Requirement: Model versions support safe lifecycle actions

Model versions SHALL support safe lifecycle actions that preserve training and
prediction traceability.

#### Scenario: Operator archives an approved inactive model

- **GIVEN** an approved model is not selected by any active deployment policy
- **WHEN** an authorized operator archives the model with a reason
- **THEN** the model SHALL be hidden from default deployable model selectors
- **AND** its artifact URI, manifest, hash, metrics, and lineage SHALL remain
  available for audit.

#### Scenario: Operator attempts to delete a policy-referenced model

- **GIVEN** a model version is referenced by an active deployment policy
- **WHEN** an operator requests model deletion
- **THEN** the system SHALL block deletion
- **AND** return the referencing policy ids and scopes.

#### Scenario: Operator deletes an unreferenced candidate model

- **GIVEN** a candidate model is not referenced by any policy or prediction
- **WHEN** an authorized operator deletes it with a reason
- **THEN** the model SHALL be removed from default registry views
- **AND** the deletion SHALL be audit logged.

### Requirement: Training runs support cleanup actions

Training runs SHALL support cleanup actions appropriate to their state.

#### Scenario: Operator deletes a failed training run

- **GIVEN** a training run failed before registering a model
- **WHEN** an authorized operator deletes the run with a reason
- **THEN** the run SHALL be hidden from default training-run lists
- **AND** failure audit data SHALL remain available in audit or archived views.

#### Scenario: Operator attempts to delete a successful training run with a model

- **GIVEN** a successful training run registered a model version
- **WHEN** an operator requests deletion
- **THEN** the system SHALL block hard deletion
- **AND** offer archive semantics that preserve model lineage.
