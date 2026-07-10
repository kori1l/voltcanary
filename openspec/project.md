# VoltCanary OpenSpec Project Notes

## Purpose

VoltCanary uses OpenSpec for non-trivial product and architecture changes before code is changed.

Use an OpenSpec change when a task affects one or more of:

- service APIs or DTO contracts;
- event schemas or topic semantics;
- database migrations or data retention;
- security, tenant isolation, or internal service boundaries;
- cross-service flows;
- frontend workflows backed by backend behavior;
- ML, analytics, alerting, or recommendation semantics.

Small local fixes can bypass OpenSpec.

## Existing Source Documents

Do not replace the existing documentation tree. Treat these documents as source context:

- `docs/api/*.md` for HTTP contracts.
- `docs/events/*.md` for event contracts.
- `docs/architecture/*.md` for system architecture.
- `docs/adr/*.md` for architectural decisions.
- `docs/flows/*.md` for manual and agent-driven verification flows.

When an OpenSpec change is implemented, update the relevant `docs/` files as part of the same work.

## Architecture Rules

Backend services use a microservice architecture with hexagonal boundaries inside each service.

- Domain code contains business concepts, invariants, and decisions.
- Application/service code coordinates use cases and delegates external access through ports or explicit adapter services.
- Controllers, message consumers, repositories, Kafka clients, HTTP clients, and SDK clients are adapters/infrastructure.
- Controllers and consumers should map transport input and delegate business behavior to application services.
- Domain code must not depend on Spring MVC controllers, Kafka clients, HTTP clients, repositories, or external SDKs.
- JPA entities may currently live in `domain`, but repository interfaces and persistence operations are treated as outbound persistence adapters.

Detailed backend architecture requirements live in `openspec/specs/backend-architecture/spec.md`.

## Implementation Expectations

- Keep ML-service backward-compatible with existing `/inference`, `/models`, and `/train` routes unless the change explicitly introduces a versioned replacement.
- Keep analytics-service resilient when ML-service is unavailable; telemetry ingestion and rule-based analytics must continue.
- Preserve maintenance exclusion semantics: `excludeFromAnalytics` affects analytics input, and `excludeFromMl` is sent to ML as `excludedIntervals`.
- Make model outputs explainable enough for UI, alerting, and interview/demo flows.
- Add tests at the boundary being changed: Python unit/API tests for ML behavior, Java tests for analytics integration/fallback behavior, and documentation updates for public contracts.
