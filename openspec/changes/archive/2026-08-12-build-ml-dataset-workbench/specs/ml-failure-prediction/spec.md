## ADDED Requirements

### Requirement: Failure-prediction dataset readiness is previewed before commit

The dataset workbench SHALL preview failure-prediction label readiness for the
selected horizon before committing an internal telemetry dataset version.

#### Scenario: Operator previews failure-prediction readiness

- **GIVEN** the operator selects `FAILURE_PREDICTION`
- **AND** provides a positive horizon
- **WHEN** dataset preview evaluates the selected device scope, metrics, and
  time range
- **THEN** the response SHALL include positive, normal, unresolved,
  maintenance-excluded, and unobservable label counts
- **AND** include label coverage status and threshold decisions.

#### Scenario: Failure horizon is invalid

- **GIVEN** the operator selects `FAILURE_PREDICTION`
- **WHEN** the horizon is missing or not positive
- **THEN** the workbench preview SHALL report a blocking reason
- **AND** the UI SHALL not allow dataset creation from that preview.

#### Scenario: Preview has insufficient positive labels

- **GIVEN** supervised failure training requires positive outcomes
- **WHEN** preview finds no confirmed failure or corrective maintenance labels
  for the selected horizon
- **THEN** the preview SHALL be blocked or warning according to configured gates
- **AND** explain that supervised failure training is not ready for the
  selection.

#### Scenario: Preview does not mutate generated labels

- **GIVEN** existing generated labels may exist for the dataset or account
- **WHEN** failure readiness preview runs
- **THEN** preview SHALL not create, delete, or replace generated labels
- **AND** explicit horizon-label generation SHALL remain a separate
  lineage-changing action.
