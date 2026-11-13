## ADDED Requirements

### Requirement: Typed generic resource dependency bindings

New configurable plan steps SHALL bind exact versioned schema/data/import/mutation resources through successful owner receipts, record local reference resolution and prevent UNKNOWN remote operations from becoming confirmed dependency facts.

#### Scenario: Compound schema creation
- **WHEN** one plan creates a schema then imports rows and publishes a rule
- **THEN** later steps use actual receipt-resolved IDs and exact validated revisions

#### Scenario: Cancelled mutation timeout
- **WHEN** a remote write is uncertain when the plan is cancelled
- **THEN** the plan retains UNKNOWN reconciliation identity and never treats cancellation as rollback



### Requirement: Model-selected typed intent planning
The assistant SHALL interpret user intent and continuation through model decisions over an authorized compact intent catalogue and relevant actual dialogue. Code SHALL NOT route free-form language by regex, keywords or previous-question wording; the model SHALL receive and interpret relevant prior questions and messages.

#### Scenario: Multi-intent request
- **WHEN** the user requests device creation, source connection and a dashboard
- **THEN** the model may propose dependent steps and the server validates their contracts before scheduling

#### Scenario: Paraphrased continuation
- **WHEN** the user refers to an earlier created resource using a different natural-language phrasing
- **THEN** the model can select a provided confirmed fact without adding a phrase matcher

#### Scenario: Known-step follow-up
- **WHEN** one persisted step is awaiting the user's reply or refinement
- **THEN** its skill may interpret that reply directly without a mandatory extra planner call, and model-returned REPLAN_REQUIRED triggers explicit replanning under the same budget if the meaning leaves the skill's scope

### Requirement: Durable dependency and receipt state
Plans SHALL preserve revisions, retained goals, explicit amendments, confirmed facts and completed receipts across turns and restart; downstream steps SHALL require successful typed dependencies and current access.

#### Scenario: Creation succeeds and connection fails
- **WHEN** a later retry resumes the workflow
- **THEN** the existing device receipt is reused and another device is not created

#### Scenario: Unknown dependency
- **WHEN** a dependency has uncertain execution
- **THEN** downstream composition waits for receipt reconciliation rather than treating the proposed output as real

#### Scenario: Plan changes
- **WHEN** the user amends pending work
- **THEN** completed steps remain recorded and affected unconfirmed reviews are invalidated

#### Scenario: Follow-up after a completed plan
- **WHEN** the user requests a dashboard for a device created in an earlier completed plan of this conversation
- **THEN** retained scoped resource events and successful receipts let the model bind the new plan to that actual device without recreating it
