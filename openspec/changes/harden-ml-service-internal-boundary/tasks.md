# Tasks

## ML Service

- [ ] Add ML-service settings for internal auth, allowed source services, and
      maximum request body size.
- [ ] Add middleware/dependency enforcement for `/train`, `/runs/{id}`,
      `/models`, `/inference`, and `/ml/*` aliases while leaving probe endpoints
      open.
- [ ] Add sanitized error responses for invalid token, missing source service,
      oversized request, and validation failures.
- [ ] Add auth failure and request rejection metrics or structured log events.
- [ ] Add FastAPI tests covering open probes, authorized requests, unauthorized
      requests, oversized bodies, and `/ml/*` aliases.

## Backend And Deploy

- [ ] Verify analytics-service `MlClient` sends the expected source-service and
      token headers for training and inference.
- [ ] Wire production Docker/Kubernetes secrets and config values for ML-service
      internal auth.
- [ ] Ensure local/test profiles explicitly disable auth or use a test token.

## Documentation And Validation

- [ ] Update `docs/api/ml-api.md` with the internal auth boundary and sanitized
      error behavior.
- [ ] Update `docs/operations/ml-operations.md` with required runtime settings.
- [ ] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [ ] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [ ] Run `npm run openspec:validate`.
