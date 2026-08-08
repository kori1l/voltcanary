## ADDED Requirements

### Requirement: Dataset versions support safe cleanup

Dataset versions SHALL be archivable or deletable only when doing so preserves
ML lineage.

#### Scenario: Operator archives a ready dataset

- **GIVEN** a `READY` dataset version exists
- **WHEN** an authorized operator archives it with a reason
- **THEN** the dataset SHALL be hidden from default dataset selectors
- **AND** immutable row references, hashes, validation reports, and lineage SHALL
  remain readable.

#### Scenario: Operator deletes a failed unreferenced dataset

- **GIVEN** a dataset version is `FAILED`
- **AND** no labels, feature snapshots, training runs, or model versions
  reference it
- **WHEN** an authorized operator deletes it with a reason
- **THEN** it SHALL be removed from default dataset lists
- **AND** the action SHALL be audit logged.

#### Scenario: Operator attempts to delete a referenced dataset

- **GIVEN** a dataset version is referenced by labels, feature snapshots,
  training runs, or model versions
- **WHEN** an operator requests hard deletion
- **THEN** the system SHALL block deletion
- **AND** return a dependency summary.
