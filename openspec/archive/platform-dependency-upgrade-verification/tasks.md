# Tasks: Platform Dependency Upgrade And Verification

## 1. Inventory

- [x] 1.1 Run `npm outdated` in the repository root.
- [x] 1.2 Run `npm outdated` in `frontend`.
- [x] 1.3 Run Maven dependency/property update discovery for `backend`.
- [x] 1.4 Run Python outdated-package discovery for `ml-service`.
- [x] 1.5 Inventory Docker Compose and Kubernetes image tags, flagging floating tags.
- [x] 1.6 Record selected target versions before editing dependency files.

## 2. Toolchain Alignment

- [x] 2.1 Choose a single Node baseline for root tooling, frontend, CI, and docs.
- [x] 2.2 Update root `.node-version`, `.nvmrc`, `package.json` engines, and OpenSpec scripts if needed.
- [x] 2.3 Update `frontend/package.json` engines to match the chosen Node baseline.
- [x] 2.4 Update `.github/workflows/ci.yml` Node version if needed.
- [x] 2.5 Confirm Java 21 and Python 3.12 or chosen replacement are consistent across CI, Docker, and docs.

## 3. Frontend Upgrade

- [x] 3.1 Upgrade React, React DOM, React Router, Vite, TypeScript, Vite React plugin, and React type packages to selected stable versions.
- [x] 3.2 Update Vite/TypeScript config for new defaults or required options.
- [x] 3.3 Fix source changes caused by upgraded React Router, React types, or TypeScript strictness.
- [x] 3.4 Regenerate `frontend/package-lock.json`.
- [x] 3.5 Run frontend verification: `npm ci`, `npx tsc --noEmit`, and `npm run build`.

## 4. ML-Service Upgrade

- [x] 4.1 Pin FastAPI, Uvicorn, Pydantic, Pydantic Settings, and HTTPX versions.
- [x] 4.2 Decide whether to add a lock/constraints file or keep pinned `requirements.txt` as the reproducibility mechanism.
- [x] 4.3 Update Pydantic model code if validation or alias behavior changed.
- [x] 4.4 Run ML verification: install dependencies and `python -m unittest discover -s app/tests`.
- [x] 4.5 Update ML setup docs if install commands change.

## 5. Backend Upgrade

- [x] 5.1 Upgrade Spring Boot and Spring Cloud as a compatible pair.
- [x] 5.2 Upgrade Testcontainers, JUnit, PostgreSQL JDBC, Flyway, Springdoc, MapStruct, Lombok, Micrometer, Kafka clients, Nimbus JOSE JWT, and MQTT client where appropriate.
- [x] 5.3 Remove redundant explicit versions that are better managed by Spring Boot BOM.
- [x] 5.4 Fix compile/test failures caused by framework changes.
- [x] 5.5 Run targeted service tests after each risky migration.
- [x] 5.6 Run full backend verification: `cd backend; mvn test`.

## 6. Container And Deployment Images

- [x] 6.1 Replace floating image tags with explicit stable tags where possible.
- [x] 6.2 Align Compose and Kubernetes image versions for shared services.
- [x] 6.3 Update Maven, Python, and Node base images if toolchain baselines changed.
- [x] 6.4 Run `docker compose config`.
- [x] 6.5 If feasible locally, run the app profile smoke startup after dependency upgrades pass.

## 7. Repository Scripts And CI

- [x] 7.1 Decide whether root `npm test` should remain OpenSpec-only or orchestrate all checks.
- [x] 7.2 Add convenience scripts for dependency verification if useful.
- [x] 7.3 Update CI cache keys and setup versions where dependency managers changed.
- [x] 7.4 Ensure CI runs OpenSpec validation, frontend build, ML tests, and backend tests.

## 8. Documentation And Final Verification

- [x] 8.1 Update README setup instructions for new Node/Python/Java baselines.
- [x] 8.2 Update any docs that mention old dependency or image versions.
- [x] 8.3 Run `npm run openspec:validate`.
- [x] 8.4 Run frontend, ML-service, backend, project-structure, and compose verification.
- [x] 8.5 Record skipped checks and reasons in the implementation final notes.
