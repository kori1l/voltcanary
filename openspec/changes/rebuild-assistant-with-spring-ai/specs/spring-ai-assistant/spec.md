## ADDED Requirements

### Requirement: Spring AI Runtime
The assistant SHALL use Spring AI 2 models, ChatClient messages and tool contracts on a compatible Spring Boot 4 platform for all active model phases.

#### Scenario: Account-scoped model invocation
- **WHEN** an authenticated account invokes the assistant
- **THEN** its selected model, credentials, authorized context and bounded output options SHALL be isolated from other accounts
- **AND** every network model exchange SHALL have a durable budget reservation and trace start without implicit framework retries

### Requirement: Reviewed Tool Execution
Spring AI tools SHALL preserve domain authorization, explicit mutation confirmation, idempotency and receipt-based recovery.

#### Scenario: Model requests a mutation
- **WHEN** the model proposes a domain mutation
- **THEN** the application SHALL compile and persist a reviewed command and require the existing confirmation/version/digest checks before execution
- **AND** an uncertain result SHALL be reconciled through existing receipts rather than repeated automatically

### Requirement: Durable Conversation Compatibility
The migrated assistant SHALL preserve existing account-scoped conversations, plans, history and stored review execution compatibility.

#### Scenario: Continue after a restart
- **WHEN** a user continues a persisted conversation after the Spring AI cutover or a process restart
- **THEN** the assistant SHALL use its bounded authorized history and exact pending question without resetting or sharing conversation state

### Requirement: Platform Migration Verification
All Java services SHALL build and retain HTTP, persistence, messaging and security behavior on the upgraded shared platform.

#### Scenario: Offline migration validation
- **WHEN** the migration is validated without paid model calls
- **THEN** backend tests and local provider fixtures SHALL verify the platform and Spring AI contracts
- **AND** missing infrastructure or failed model-quality acceptance SHALL remain explicit rather than being reported as successful
