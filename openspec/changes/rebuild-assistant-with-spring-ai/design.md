# Design

## Platform
Use a released Spring Boot 4.0 maintenance version, Spring Cloud 2025.1 and Spring AI 2.0. Keep Java 21. Let Boot manage framework, Kafka, Jackson and Micrometer families instead of mixing Boot 3 pins with Framework 7. Migrate modular web/test/Flyway starters and relocated test annotations. Override Boot 4.0.9's H2 2.4.240 with H2 2.5.252 to fix the observed cross-connection CHECK constraint regression (#4302/#4308); do not weaken production schema constraints. Preserve Jackson 2 application contracts using Boot's supported compatibility module during the platform migration; framework and Spring AI may use Jackson 3 internally.

## Assistant
Spring AI provider models are constructed from the authenticated account's resolved provider/model configuration, never a global mutable API-key/model singleton. ChatClient handles exchanges, with explicit bounded messages, structured response options and per-call tool definitions. Framework retry templates have one attempt. Every additional model exchange requires its own durable budget reservation and trace start. Existing routing policy and PINNED selection remain authoritative.

Tools expose scoped context reads and reviewed domain commands. A model tool request is not authorization to mutate. Domain compilers, review digest/version checks, explicit confirmation, access revalidation and receipt-based reconciliation retain ownership of mutations. Shared singleton clients must not retain conversation, authorization or account-specific tools.

Existing persisted conversations and plans remain the source of truth. Their bounded authorized projections become Spring AI messages; introducing an independent default in-memory history store is prohibited. Existing durable steps remain recoverable after process restart.

## Migration and Evidence
First make the backend build/test on Boot 4. Then replace all active model transports with Spring AI, integrate tools/context and remove redundant runtime paths. Verify with local HTTP fixtures (no provider credentials), account-isolation and reservation/confirmation tests, full backend tests and repository/OpenSpec validation. Record unavailable infrastructure and any failed checks explicitly. Historical failed model quality remains failed until separately qualified.
