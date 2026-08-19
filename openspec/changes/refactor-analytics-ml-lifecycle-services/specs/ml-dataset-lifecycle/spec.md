# ml-dataset-lifecycle Specification

## ADDED Requirements

### Requirement: Dataset Lifecycle Implementation Is Decomposed

Dataset lifecycle behavior SHALL be implemented through focused services for
preview, validation, materialization, lifecycle actions, and response mapping.

#### Scenario: Internal telemetry preview is changed

- **WHEN** the preview contract or readiness logic changes
- **THEN** candidate window scoring, timeline construction, label readiness,
  and quality gate validation SHALL remain separately testable
- **AND** dataset creation SHALL continue to revalidate preview identity before
  materializing immutable rows.

#### Scenario: Dataset cleanup is changed

- **WHEN** archive or delete behavior is changed
- **THEN** dependency impact checks SHALL remain isolated from preview and row
  materialization logic
- **AND** referenced lineage SHALL remain readable.
