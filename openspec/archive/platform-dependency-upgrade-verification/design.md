# Design: Platform Dependency Upgrade And Verification

## Current Dependency Surfaces

### Backend

Backend uses Java 21 and a Maven parent at `backend/pom.xml`. Dependency versions are centralized through properties and BOM imports:

- Spring Boot
- Spring Cloud
- Testcontainers
- JUnit
- PostgreSQL JDBC
- Flyway
- MapStruct
- Lombok
- Springdoc
- Micrometer
- Spring Kafka / Kafka clients
- Nimbus JOSE JWT
- Eclipse Paho MQTT

### Frontend

Frontend uses React, React DOM, React Router, Vite, TypeScript, and Vite React plugin. CI already uses Node 22, but `frontend/package.json` still permits Node `>=14.18`.

### ML-Service

ML-service uses FastAPI, Uvicorn, Pydantic, Pydantic Settings, and HTTPX. Versions are currently unpinned, so installs are not reproducible.

### Tooling And Runtime Images

Docker Compose and Kubernetes manifests pin most images but also include floating tags such as `timescale/timescaledb:latest-pg16`, `eclipse-mosquitto:2`, and `ghcr.io/home-assistant/home-assistant:stable`.

OpenSpec is available through root npm scripts using `@fission-ai/openspec@1.6.0` and temporary Node `20.19.0`.

## Upgrade Strategy

Use a staged upgrade sequence:

1. Toolchain alignment.
2. Frontend dependencies.
3. ML-service dependencies.
4. Backend dependencies.
5. Container/runtime images.
6. CI and developer documentation.

Each stage must have a local verification command before moving to the next stage. Failures should be fixed immediately rather than skipped into a later stage.

## Version Selection Rules

- Prefer the latest stable release line available at implementation time.
- Avoid prerelease, RC, milestone, nightly, or snapshot versions unless a package is already using a prerelease line.
- Respect framework compatibility matrices, especially Spring Boot and Spring Cloud.
- Prefer BOM-managed versions for Spring ecosystem dependencies instead of overriding individual transitive dependencies.
- Prefer pinned Python dependencies or a generated lock/constraints file for reproducibility.
- Prefer explicit container image tags over floating tags.
- Prefer one Node baseline across root tooling, frontend, CI, and docs.

## Verification Model

Verification must cover:

- OpenSpec validity.
- Frontend typecheck and production build.
- ML-service unit/API tests.
- Backend Maven tests.
- Project structure checks.
- Docker Compose startup or at least config validation for changed services.
- CI workflow consistency.

Suggested verification commands:

```powershell
npm run openspec:validate
cd frontend; npm ci; npx tsc --noEmit; npm run build
cd ml-service; python -m pip install -r requirements.txt; python -m unittest discover -s app/tests
cd backend; mvn test
powershell -ExecutionPolicy Bypass -File scripts/check-project-structure.ps1
docker compose config
```

If dependency upgrades require code changes, add targeted tests before or alongside the migration.

## Handling Breaking Changes

Because the project is pre-production, breaking changes are acceptable when they produce a cleaner stack. The implementation should:

- update application code directly to the new API;
- remove obsolete compatibility code;
- update tests to represent desired behavior;
- update docs and setup instructions;
- avoid temporary shims unless they are needed to keep a staged migration understandable.

## Risk Areas

- Spring Boot/Spring Cloud compatibility mismatch.
- React Router major-version route API changes.
- Vite major-version config and Node engine requirements.
- Pydantic/FastAPI validation behavior changes.
- Flyway migration behavior changes.
- Testcontainers image compatibility.
- Floating infrastructure image changes.
- CI using a different runtime than local development metadata.
