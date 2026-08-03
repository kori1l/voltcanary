## ADDED Requirements

### Requirement: Feature snapshots support safe cleanup

Feature snapshots SHALL support archive or delete actions with reference checks.

#### Scenario: Operator archives an obsolete feature snapshot

- **GIVEN** a feature snapshot is not needed in default selectors
- **WHEN** an authorized operator archives it with a reason
- **THEN** the snapshot SHALL be hidden from default feature snapshot selectors
- **AND** row counts, hashes, artifact references, and training lineage SHALL
  remain readable.

#### Scenario: Operator attempts to delete a model-referenced snapshot

- **GIVEN** a feature snapshot is referenced by a training run or model version
- **WHEN** an operator requests hard deletion
- **THEN** the system SHALL block deletion
- **AND** return the referencing training run or model ids.

#### Scenario: Operator deletes an unreferenced failed snapshot

- **GIVEN** a feature snapshot failed or was never referenced
- **WHEN** an authorized operator deletes it with a reason
- **THEN** it SHALL be removed from default feature snapshot lists
- **AND** the action SHALL be audit logged.
