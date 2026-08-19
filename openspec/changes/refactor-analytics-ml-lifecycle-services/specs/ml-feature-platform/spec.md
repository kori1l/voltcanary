# ml-feature-platform Specification

## ADDED Requirements

### Requirement: Feature Generation Is Source-Specific And Testable

Feature generation SHALL separate source-specific row handling from snapshot
lifecycle commands and response mapping.

#### Scenario: Internal telemetry features are generated

- **GIVEN** a ready internal telemetry dataset version exists
- **WHEN** features are generated
- **THEN** internal telemetry row selection and transformations SHALL be covered
  by a focused feature builder
- **AND** imported dataset row handling SHALL not share hidden mutable state
  with that builder.

#### Scenario: Feature snapshot lifecycle is changed

- **WHEN** feature snapshot archive or delete behavior changes
- **THEN** dependency checks SHALL remain separate from feature value
  generation
- **AND** feature schema, config, and data hashes SHALL remain stable for
  unchanged inputs.
