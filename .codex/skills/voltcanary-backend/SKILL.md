---
name: voltcanary-backend
description: VoltCanary Java backend workflow for Spring Boot services, Maven modules, REST controllers, internal service boundaries, Kafka consumers/producers, Flyway migrations, shared common modules, and backend tests. Use when Codex modifies or reviews files under backend/ or backend-facing API/event contracts.
---

# VoltCanary Backend

## Before Editing

Use `$voltcanary-openspec` first for non-trivial API, event, database,
security, deployment, ML/analytics, or cross-service behavior changes.

Read the local service shape before editing:

- Parent build: `backend/pom.xml`
- Shared modules: `backend/common/common-events`, `common-web`,
  `common-security`, `common-test`
- Services: `api-gateway`, `identity-service`, `device-service`,
  `ingestion-service`, `simulator-service`, `ai-agent-service`,
  `telemetry-service`, `analytics-service`, `alert-service`
- Contracts: `docs/api/*.md`, `docs/events/*.md`,
  `docs/architecture/02-service-boundaries.md`

## Architecture Rules

Preserve the hexagonal boundary described in `openspec/project.md` and
`openspec/specs/backend-architecture/spec.md`:

- Keep business decisions in domain/application services.
- Keep controllers, Kafka consumers/producers, repositories, HTTP clients, and
  SDK clients as adapters.
- Do not make domain code depend on controllers, repositories, Kafka, HTTP, or
  external SDKs.
- Keep analytics resilient when ML service is unavailable.
- Preserve maintenance exclusion semantics: `excludeFromAnalytics` affects
  analytics input; `excludeFromMl` is sent to ML as `excludedIntervals`.
- Keep runtime configuration in `config` packages as typed
  `@ConfigurationProperties` or bean wiring. Do not add new generic
  `*ConfigService` or `*SettingsService` classes in `service` packages for
  user-managed policy, preferences, simulator YAML, or model-selection behavior;
  use business names such as policy, preferences, profile, selection, or
  lifecycle use cases.
- Keep application services focused on one use case or closely related
  workflow. When a production `@Service` is already large, has many constructor
  collaborators, or owns validation, defaults, serialization, persistence,
  external I/O, and DTO mapping together, extract focused collaborators before
  adding more behavior.
- Avoid wildcard imports and compressed multi-statement one-line methods in
  production Java code; they make boundary drift harder to review.

## Implementation Notes

Public endpoints usually live in `controller` packages; internal endpoints use
`/internal/*`. Java services expose `GET /actuator/health` and
`GET /internal/ping`.

For database changes, add Flyway migrations under the owning service's
`src/main/resources/db/migration` directory and update OpenSpec/docs when the
schema or retention semantics change.

For Kafka changes, update the owning common event type and `docs/events/*.md`
with topic names, versions, and compatibility expectations.

## Validation

Run the narrowest meaningful Maven command:

```powershell
cd backend
mvn -pl services/<service-name> -am test
```

Run `mvn test` from `backend/` for shared modules or broad backend changes.
