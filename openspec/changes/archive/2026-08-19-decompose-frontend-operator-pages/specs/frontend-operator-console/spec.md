# Frontend Operator Console Specification

## ADDED Requirements

### Requirement: Pages Compose Focused Workflow Modules

Frontend route pages SHALL act as route shells that compose focused workflow
hooks, model helpers, and presentational components.

#### Scenario: Device details gains workflow behavior

- **GIVEN** the device details route already includes telemetry, analytics,
  alerts, maintenance, registry, and simulation workflows
- **WHEN** new behavior is added to one of those workflows
- **THEN** the behavior SHALL be added to a focused feature hook or component
  before the route page grows further
- **AND** the route page SHALL keep route params, tab selection, and high-level
  composition responsibilities.

#### Scenario: ML Settings gains lifecycle behavior

- **GIVEN** ML Settings already includes settings, datasets, labels, training,
  models, policies, and monitoring workflows
- **WHEN** a lifecycle behavior changes
- **THEN** data loading, draft persistence, confirmation prompts, polling, and
  business validation SHALL be implemented in focused hooks or model helpers
- **AND** tab components SHALL receive explicit data and command props.

### Requirement: Browser Storage Is Accessed Through Typed Hooks

Frontend localStorage/sessionStorage use SHALL be wrapped in typed helpers or
hooks for operator workflow state.

#### Scenario: A draft or chart preference is persisted

- **WHEN** a page needs to persist form drafts, selected metrics, chart density,
  source ids, or async job status
- **THEN** storage read/write behavior SHALL live behind a typed helper or hook
- **AND** parse failures SHALL fall back to safe defaults without breaking page
  rendering.

### Requirement: Large Page Touches Include Decomposition

Large operator pages SHALL not receive additional independent workflow behavior
without extraction.

#### Scenario: A change touches a large page

- **GIVEN** a page is over the agreed reviewability threshold or owns multiple
  independent workflows
- **WHEN** a feature change touches that page
- **THEN** the change SHALL extract the affected workflow into feature modules
  or record an explicit OpenSpec task explaining why extraction is deferred
- **AND** tests SHALL cover the extracted helper, hook, or component boundary.
