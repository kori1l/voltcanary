## ADDED Requirements

### Requirement: Task-scoped assistant context

Assistant requests SHALL summarize allowlisted current task entities, intervals and filters using readable names, without conveying credentials or granting resource authorization.

#### Scenario: ML and maintenance request

- **GIVEN** a selected dataset or operation is visible
- **WHEN** the operator opens the assistant
- **THEN** the preview and payload retain that selection and time window rather than generic Console context.
