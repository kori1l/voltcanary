## ADDED Requirements

### Requirement: Manual labels support audited deletion

Manual ML labels SHALL be removable only with audit metadata, while generated
label lineage remains reproducible.

#### Scenario: Operator deletes a manual label

- **GIVEN** a manually created label exists
- **WHEN** an authorized operator deletes it with a reason
- **THEN** the label SHALL be excluded from future training label sets
- **AND** the deletion SHALL be visible in label audit history.

#### Scenario: Operator attempts to delete generated labels without regeneration

- **GIVEN** generated horizon labels exist for a dataset and generation config
- **WHEN** an operator requests hard deletion of generated labels
- **THEN** the system SHALL require regeneration or archival semantics
- **AND** preserve the previous lineage hash in audit history.
