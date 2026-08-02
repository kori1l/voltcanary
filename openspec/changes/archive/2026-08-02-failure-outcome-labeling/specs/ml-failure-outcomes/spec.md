## ADDED Requirements

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
