## ADDED Requirements

### Requirement: Feature snapshots are listable for training selection

Analytics-service SHALL expose account-owned feature snapshots so operators and
clients can choose an existing snapshot before training.

#### Scenario: Operator lists feature snapshots

- **GIVEN** feature snapshots exist for an account
- **WHEN** the operator requests feature snapshots
- **THEN** analytics-service SHALL return account-owned snapshots newest first
- **AND** archived or deleted snapshots SHALL be excluded from default results.

#### Scenario: Operator filters snapshots by dataset version

- **GIVEN** feature snapshots exist for multiple dataset versions
- **WHEN** the operator requests snapshots for one dataset version
- **THEN** analytics-service SHALL return only snapshots for that dataset
  version.

#### Scenario: Snapshot metadata supports training compatibility

- **GIVEN** the frontend receives feature snapshot list results
- **WHEN** it renders training selection
- **THEN** each snapshot SHALL include dataset version id, model family, horizon
  days, template, row count, hashes, lifecycle status when applicable, and
  created timestamp needed to explain compatibility.
