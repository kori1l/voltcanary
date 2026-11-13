# Change: Rebuild the assistant with Spring AI 2 and upgrade Spring Boot

## Why
The user explicitly requests a complete Spring AI rebuild and authorizes the prerequisite Spring upgrade. Handwritten provider HTTP clients and parallel orchestration paths increase maintenance and make consistent model/tool behavior difficult.

## What Changes
- Upgrade the shared backend to Spring Boot 4 and a compatible Spring Cloud release, including modular starters, tests and JSON integration.
- Adopt Spring AI 2 for provider models, ChatClient, structured responses, messages/context and tool integration across assistant phases.
- Route every model invocation through the durable account-scoped budget and trace boundary; disable framework retries that would escape reservations.
- Preserve existing conversation/review/plan identifiers, persisted history, confirmations, domain authorization, idempotency and uncertain-result reconciliation.
- Retire handwritten provider transport once its replacement passes contract tests. Preserve reading and execution of previously stored reviews.
- Update architecture and verification guidance; qualify the new runtime with offline provider fixtures and available integration tests.

## Impact
All backend Maven modules share the platform upgrade. Assistant runtime, provider adapters, tests and architecture guidance change. Existing frontend API contracts remain compatible unless an explicitly documented extension is required. Python ML lifecycle and inference remain owned by ml-service.

## Relationship to Existing Work
This explicitly requested migration takes precedence over the A5/A6 implementation queue. It reuses their domain contracts and durable state and does not claim their unfinished quality acceptance. No paid model evaluation is inferred from this request.
