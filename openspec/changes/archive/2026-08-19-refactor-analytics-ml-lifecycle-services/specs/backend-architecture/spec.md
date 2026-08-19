# Backend Architecture

## ADDED Requirements

### Requirement: Analytics ML Services Are Use-Case Scoped

Analytics-service ML application services SHALL keep independent lifecycle
workflows in separate use-case components.

#### Scenario: Dataset lifecycle behavior is extended

- **GIVEN** analytics-service owns ML dataset preview, validation,
  materialization, listing, detail, archive, and delete workflows
- **WHEN** one of those workflows changes
- **THEN** the implementation SHALL route the behavior through a focused
  collaborator for that workflow
- **AND** SHALL NOT add more preview, materialization, lifecycle, hashing, and
  DTO mapping behavior to a single all-purpose dataset service.

#### Scenario: Model deployment behavior is extended

- **GIVEN** analytics-service owns model approval, model lifecycle, deployment
  policy changes, policy resolution, and audit
- **WHEN** deployment behavior changes
- **THEN** policy resolution and lifecycle commands SHALL remain in distinct
  components
- **AND** model registry facades SHALL stay thin if retained for controller
  compatibility.

### Requirement: Recalculation Orchestration Delegates Decisions

Analytics recalculation SHALL keep job orchestration separate from calculators,
ML request construction, fallback decisions, persistence, and event publishing.

#### Scenario: ML fallback behavior changes

- **WHEN** analytics-service changes how it handles unavailable ML-service or
  incompatible model features
- **THEN** fallback selection and traceability persistence SHALL be implemented
  behind explicit collaborators
- **AND** the recalculation entry point SHALL remain an orchestrator rather than
  a combined calculator, adapter, serializer, and repository owner.
