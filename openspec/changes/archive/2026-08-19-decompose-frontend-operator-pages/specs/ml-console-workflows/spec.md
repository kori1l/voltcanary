# ml-console-workflows Specification

## ADDED Requirements

### Requirement: ML Console Workflow Logic Is Modular

ML Settings workflow logic SHALL be decomposed into focused hooks, model
helpers, and tab components while preserving the existing operator workflow.

#### Scenario: Label generation workflow changes

- **WHEN** async horizon label generation behavior changes
- **THEN** polling, persisted job state, status messages, and retry handling
  SHALL be owned by a focused workflow hook or helper
- **AND** the ML Settings route page SHALL not own the full polling
  implementation.

#### Scenario: Training workflow changes

- **WHEN** training prerequisites, drafts, model-family defaults, or feature
  snapshot selection behavior changes
- **THEN** validation and draft persistence SHALL be independently testable
- **AND** tab rendering SHALL consume the validated workflow state through
  explicit props.
