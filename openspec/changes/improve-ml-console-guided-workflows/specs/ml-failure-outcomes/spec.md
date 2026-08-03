## ADDED Requirements

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
