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

Feature-sliced packages are allowed only when they are documented inside the
service and reduce navigation cost. Do not mix feature-sliced and layered
layouts inside the same service.

Run `powershell -ExecutionPolicy Bypass -File scripts/check-project-structure.ps1`
before merging package or configuration changes.
