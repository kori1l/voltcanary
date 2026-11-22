# Repository Structure

Backend services live under `backend/services`. Shared backend code lives under
`backend/common`.

Java services use a small layered package layout:

- `controller` for public and internal REST controllers.
- `service` for application services, orchestration, and calculation helpers.
- `repository` for Spring Data repositories.
- `domain` for JPA entities, enums, and domain value objects.
- `dto` for request and response records.
- `mapper` for DTO/domain mapping helpers.
- `config` for Spring configuration and typed `@ConfigurationProperties`.
- `security` for authentication and authorization helpers.
- `exception` for exception handlers and error contracts.

Feature packages such as `vcl`, `configurable`, and `observation` keep their
application operations, domain ports and adapters together. Preserve the owning
feature boundary when it coexists with older layered packages; package names do
not replace dependency checks. Controllers delegate application operations rather
than querying repositories or sending Kafka messages directly. Domain types do
not depend on controllers, services, repository interfaces or transport DTOs.

`common-business-contracts` holds JDK-only business records such as tariffs,
operating schedules, operational facts and energy-meter definitions. HTTP helpers
stay in `common-web`; service-specific clients stay with the owning service.

Run `powershell -ExecutionPolicy Bypass -File scripts/check-project-structure.ps1`
before merging package or configuration changes.

## Quality checks

From the repository root, run `npm run format:check` and
`npm run architecture:check`. Formatting uses pinned Prettier and the Java plugin;
`.editorconfig` and `.gitattributes` keep whitespace and line endings consistent.
The architecture check parses production Java imports and qualified references,
checks shared business contract dependencies, and rejects runtime Maven dependencies
between service modules. The structure risk report includes feature-package
services and Lombok constructor dependencies; its size thresholds flag review
risks rather than establishing correctness.

Frontend uses `npm run lint`, `npm run typecheck`, `npm run test`, and
`npm run build` from `frontend`. ML uses Python 3.12 with
`pip install -r requirements-dev.txt`, `python -m ruff check app`,
`python -m ruff format --check app`, and
`python -m unittest discover -s app/tests` from `ml-service`.
For a local backend run without Docker, use `mvn -Plocal-tests test` from
`backend`; this excludes the `docker` tag. Default CI backend tests keep container
coverage. Passing local tests does not qualify PostgreSQL/Timescale, Kafka, S3 or
multi-service deployment behavior.

Native PostgreSQL qualification for ML history can run without Docker. Point
`ML_TEST_POSTGRES_DSN` at an isolated local test database, then run
`python -m unittest app.tests.test_postgres_training_history -v` from `ml-service`.
The four cases cover shared execution across processes, process-exit recovery,
immutable outcomes, and loss of the owning database session. The test role needs
permission to initialize the history table and terminate its own sessions.
Ordinary discovery skips these four cases when the variable is absent.
