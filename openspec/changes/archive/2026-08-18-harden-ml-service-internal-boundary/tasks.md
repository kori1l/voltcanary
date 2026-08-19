# Tasks

## ML Service

- [x] Add ML-service settings for internal auth, allowed source services, and
      maximum request body size.
- [x] Add middleware/dependency enforcement for `/train`, `/runs/{id}`,
      `/models`, `/inference`, and `/ml/*` aliases while leaving probe endpoints
      open.
- [x] Add sanitized error responses for invalid token, missing source service,
      oversized request, and validation failures.
- [x] Add auth failure and request rejection metrics or structured log events.
- [x] Add FastAPI tests covering open probes, authorized requests, unauthorized
      requests, oversized bodies, and `/ml/*` aliases.

## Backend And Deploy

- [x] Verify analytics-service `MlClient` sends the expected source-service and
      token headers for training and inference.
- [x] Wire production Docker/Kubernetes secrets and config values for ML-service
      internal auth.
- [x] Ensure local/test profiles explicitly disable auth or use a test token.

## Documentation And Validation

- [x] Update `docs/api/ml-api.md` with the internal auth boundary and sanitized
      error behavior.
- [x] Update `docs/operations/ml-operations.md` with required runtime settings.
- [x] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [x] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [x] Run `npm run openspec:validate`.
