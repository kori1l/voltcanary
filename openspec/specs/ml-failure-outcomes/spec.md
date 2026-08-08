# ml-failure-outcomes Specification

## Purpose
Define how structured maintenance outcomes become deterministic, auditable ML
labels for supervised failure-prediction datasets.
## Requirements
### Requirement: Maintenance outcomes are structured training signals

The system SHALL capture structured maintenance outcomes separately from
maintenance exclusion windows.

#### Scenario: Operator records confirmed failure outcome

- **GIVEN** a maintenance operation exists for a device
- **WHEN** an authorized operator records a confirmed failure outcome
- **THEN** the system SHALL store outcome type, observed time, device, severity, optional component/failure mode, source, and audit metadata
- **AND** analytics-service SHALL be able to consume the outcome for label generation.

### Requirement: ML labels distinguish failure outcomes from maintenance windows

ML labels SHALL include outcome-specific values needed for supervised failure
prediction.

#### Scenario: Label is generated from corrective maintenance

- **GIVEN** an outcome is recorded as `CORRECTIVE_MAINTENANCE`
- **WHEN** labels are generated for a failure-prediction dataset
- **THEN** rows before the outcome within the configured horizon SHALL be eligible for positive failure labels
- **AND** the label SHALL reference the source outcome.

#### Scenario: Preventive maintenance is not treated as failure by default

- **GIVEN** an outcome is recorded as `PREVENTIVE_MAINTENANCE`
- **WHEN** labels are generated
- **THEN** the outcome SHALL not be treated as a confirmed failure unless the label generation config explicitly includes it.

### Requirement: Horizon labels are deterministic and auditable

Label generation SHALL record horizon, generation version, source outcomes, and
lineage.

#### Scenario: Labels are regenerated with the same inputs

- **GIVEN** the same dataset version, outcomes, and label config
- **WHEN** label generation runs twice
- **THEN** the generated label windows and label lineage hash SHALL match.

### Requirement: Label quality is visible before supervised training

Dataset detail SHALL show whether labels are sufficient for supervised failure
prediction.

#### Scenario: Dataset lacks positive outcomes

- **GIVEN** a dataset has no confirmed failures or corrective maintenance outcomes for the selected horizon
- **WHEN** an operator reviews supervised training readiness
- **THEN** the system SHALL report insufficient positive labels
- **AND** supervised failure training SHALL be blocked or clearly marked unsupported.

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

### Requirement: Label UI separates generated labels from manual corrections

The Labels tab SHALL separate generated horizon-label workflows from manual
label creation and correction workflows.

#### Scenario: Operator generates horizon labels

- **GIVEN** a `READY` dataset version has maintenance outcomes in range
- **WHEN** the operator opens the generated-label workflow
- **THEN** the UI SHALL ask only for dataset version, horizon days, and whether
  preventive maintenance should count as positive
- **AND** the UI SHALL show generated, positive, normal, unobservable counts and
  lineage hash after generation.

#### Scenario: Operator manually corrects a label

- **GIVEN** generated labels exist for a dataset version
- **WHEN** the operator opens the manual correction workflow
- **THEN** the UI SHALL show label rows with filters for device, metric, label
  type, source, and time range
- **AND** manual edits SHALL require a reason
- **AND** the UI SHALL show audit history for edited labels.

### Requirement: Label readiness is visible before training

The Labels tab and Training tab SHALL surface whether labels satisfy the chosen
failure-prediction gates.

#### Scenario: Labels satisfy demo training gates

- **GIVEN** the selected dataset has positive and normal horizon labels
- **WHEN** the operator opens failure-prediction training
- **THEN** the UI SHALL show label coverage, positive count, normal count, and
  unobservable count
- **AND** indicate whether the configured minimum positive labels and coverage
  gates can pass.
