## ADDED Requirements

### Requirement: ML console preserves feature snapshot inventory

The ML Settings console SHALL show available feature snapshots and use that
inventory when queueing training.

#### Scenario: Operator generates multiple snapshots

- **GIVEN** the operator creates anomaly and failure-prediction feature
  snapshots
- **WHEN** the operator opens Training
- **THEN** the snapshot selector SHALL show compatible existing snapshots rather
  than only the most recently generated snapshot.

#### Scenario: Training has no compatible snapshot

- **GIVEN** no current snapshot matches the selected dataset, model family, and
  horizon
- **WHEN** the operator opens Training
- **THEN** the UI SHALL block queueing training
- **AND** explain that a feature snapshot must be generated or selected first.
