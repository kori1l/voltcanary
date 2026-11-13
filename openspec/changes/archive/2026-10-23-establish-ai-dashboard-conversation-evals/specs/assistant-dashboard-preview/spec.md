## ADDED Requirements

### Requirement: Reproducible multi-turn dashboard evaluation
The evaluation harness SHALL exercise the actual authenticated dashboard conversation path using versioned scenarios and fresh isolated state, and SHALL score the requested outcome after each turn.

#### Scenario: User's compound tram refinement
- **WHEN** the frozen tram conversation creates a dashboard, supplies its name and moves current-value cards while requesting door history
- **THEN** the report identifies each trial and turn and verifies the name, requested content and preservation of unrelated widget identities and data

#### Scenario: Regression grader is challenged
- **WHEN** a candidate has a valid schema but omits a requested change or modifies an unrelated binding
- **THEN** the grader fails that outcome without depending on the assistant's success wording

### Requirement: Honest repeated quality reporting
The evaluation harness SHALL distinguish assertions, trials and whole conversations, report repeatability and unavailable dependencies, and SHALL NOT treat mocks or missing provider calls as live model acceptance.

#### Scenario: One response passes many assertions
- **WHEN** one real model response passes21 assertions
- **THEN** the report records one successful trial and21 passed assertions rather than21 successful independent model runs

#### Scenario: Provider cannot be reached
- **WHEN** a required live provider is unavailable
- **THEN** the run cannot pass the live quality gate and reports availability separately from semantic correctness

### Requirement: Isolated evaluation evidence
Evaluation SHALL use ordinary authenticated fixture setup and recoverable cleanup, preserve existing user resources, and exclude credentials and private reasoning from committed fixtures and reports.

#### Scenario: Execute reviewed fixture
- **WHEN** the isolated execution job confirms a prepared dashboard
- **THEN** the created private draft matches the exact reviewed definition and cleanup archives only the job's own resource
