# Agent Instructions

## Project Snapshot

VoltCanary is a predictive IoT analytics platform for connected appliances and
small physical assets. The repository is a monorepo with:

- `backend/` - Java 21, Spring Boot 3, Spring Cloud Gateway, Maven modules.
- `ml-service/` - Python 3.12, FastAPI ML lifecycle and inference service.
- `frontend/` - React 19, TypeScript, Vite web console.
- `deploy/` - Docker Compose, Kubernetes, Helm, observability manifests.
- `docs/` - architecture, API contracts, event contracts, ADRs, flows.
- `openspec/` - source of truth for non-trivial changes.

Use `rg` / `rg --files` first for repository search. On Windows PowerShell, if
`npm.ps1` is blocked by execution policy, check `Get-ExecutionPolicy -List` and
set `CurrentUser` to `RemoteSigned`.

## Project Skills

Project-local skills live under `.codex/skills/`. Load only the skills relevant
to the task:

- `$voltcanary-openspec` - OpenSpec selection, apply, validation, and archiving.
- `$voltcanary-backend` - Java services, Maven modules, REST, Kafka, Flyway.
- `$voltcanary-ml-service` - FastAPI ML, datasets, features, model artifacts.
- `$voltcanary-frontend` - React/Vite console, API clients, UI workflow tests.
- `$voltcanary-local-ops` - Compose, local Kubernetes, ports, observability.
- `$voltcanary-docs-guidelines` - docs, ADRs, API/event contracts, guidelines.

For cross-service or product-facing work, start with `$voltcanary-openspec`,
then load the surface-specific skills needed for implementation.

## OpenSpec Workflow

VoltCanary uses OpenSpec for non-trivial product, architecture, dependency, API,
data, security, deployment, ML, analytics, alerting, recommendation, frontend
workflow, and cross-service changes.

When a user references an OpenSpec change, spec, or task, or when the requested
work is non-trivial, use `$voltcanary-openspec` before editing implementation
files. Keep change selection, task tracking, validation, and archiving details
inside that skill, not in this file.

Small local fixes that do not affect those surfaces may bypass OpenSpec.

## Validation Baselines

Use the narrowest meaningful validation first, then broaden when the blast
radius justifies it:

- Repository/OpenSpec: `npm run openspec:validate`
- Backend all modules: `cd backend; mvn test`
- Backend one service: `cd backend; mvn -pl services/<service-name> -am test`
- ML service: `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`
- Frontend: `cd frontend; npm run typecheck; npm run test; npm run build`

Keep documentation in `docs/` aligned when OpenSpec-governed behavior changes.
Do not commit generated artifacts, logs, local environment files, or model
outputs unless the task explicitly requires them.
