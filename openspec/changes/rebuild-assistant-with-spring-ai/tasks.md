## 1. Platform
- [x] 1.1 Upgrade shared Spring Boot/Cloud dependencies and migrate modular starters and test imports.
- [x] 1.2 Preserve HTTP/JSON, persistence, Kafka and security behavior with all backend tests.

## 2. Spring AI Runtime
- [x] 2.1 Implement account-scoped Spring AI provider/model construction with bounded timeouts and no hidden retries.
- [x] 2.2 Replace handwritten transports for planner, skill, correction, assessment and legacy-compatible requests with ChatClient exchanges and structured output.
- [x] 2.3 Integrate scoped tools and durable conversation context through Spring AI while preserving reservation, access, confirmation and receipt boundaries.
- [x] 2.4 Remove redundant active provider/orchestration paths, preserving stored review compatibility.

## 3. Verification and Documentation
- [x] 3.1 Verify provider wire contracts, model selection, usage, incomplete output and failure handling with offline fixtures.
- [x] 3.2 Verify multi-turn workflows, tenant isolation, confirmation, cancellation and uncertain execution behavior.
- [x] 3.3 Update architecture, operations and agent guidance and align A5/A6 ownership.
- [x] 3.4 Run backend tests, structure checks and strict OpenSpec validation; record evidence and remaining limitations.

## Evidence

- Shared platform and all service production sources compile on Spring Boot 4.0.9 / Spring Cloud 2025.1.4; Spring AI dependencies resolve at 2.0.2.
- `SpringAiExchangeTest`: native OpenAI/Anthropic/Gemini HTTP fixtures, credential/model/dialogue isolation, schema/tools, truncation/usage and no SDK/framework retries; 9 tests passed in the final assistant run, including preservation of custom compatible-provider URL prefixes.
- `PrimaryPlannerProjectionTest`: 9 tests passed, including a real Spring AI exchange through the durable reservation ledger, canonical replay without a second HTTP call, and a model-run diagnostic outage.
- Existing intent, context, history, review, confirmation, cancellation and uncertain-receipt scenarios pass in ai-agent-service.
- Python evaluation harness: 52 offline tests passed with `python -m unittest discover -s scripts/ai_dashboard_eval -t .`.
- Repository structure check passed with existing risk warnings; strict OpenSpec validation passed (87 items). All 15 reactor modules passed `mvn -B -f backend/pom.xml -fae test` (2026-10-29). The final assistant follow-up passed `mvn -B -f backend/pom.xml -pl services/ai-agent-service -am test`.
- Historical A5/A6 quality acceptance remains unchanged. No paid model calls or changes to saved provider credentials were made.

- Final Surefire aggregate: 862 Java tests, 0 failures, 0 errors, 0 skipped. Local ignored logs: `artifacts/spring-ai-backend-accepted.log` and `artifacts/spring-ai-assistant-accepted.log`.
- H2 2.5.252 fixes the Boot-managed 2.4.240 cross-connection constraint regression without changing production constraints. `JacksonRestClientsTest` checks ISO dates, forward-compatible DTOs, Jackson 2 nodes and raw String replies; assistant domain tests also retain JSON priority with optional YAML codecs on the classpath.
- `LegacyAssistantContinuationTest` verifies importing the original request and exact prior question once, replay idempotency and rejection of a different owner. Legacy reads/receipts and current plan routing-policy identities are retained.
- Running application containers were not rebuilt/restarted; provider fixtures and disposable integration-test containers supplied offline verification. Live model/workflow quality remains subject to the existing A5/A6 qualification gates.
