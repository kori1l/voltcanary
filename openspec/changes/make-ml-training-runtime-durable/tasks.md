# Tasks

## Contract And ML Service

- [ ] Extend ML training request/response schemas with `trainingRunId`,
      `idempotencyKey`, and deterministic artifact target metadata.
- [ ] Implement duplicate-safe ML-service training behavior for repeated
      requests with the same idempotency key.
- [ ] Reject conflicting retries for the same idempotency key.
- [ ] Add staged artifact persistence or partial-artifact cleanup.
- [ ] Add Python-side cancellation checkpoints around major training phases.
- [ ] Add configurable training timeout/resource limits.
- [ ] Clarify or replace `/runs/{id}` so production status does not depend on
      process memory.
- [ ] Add ML-service tests for retry, conflict, cancellation, timeout, and
      partial artifact cleanup.

## Analytics Service

- [ ] Pass analytics `trainingRunId` and idempotency metadata to ML-service.
- [ ] Update worker retry behavior to reuse the same idempotency key when
      retrying the same claimed run.
- [ ] Update cancellation flow to signal or expose cancellation to ML-service
      checkpoints.
- [ ] Add analytics-service tests for duplicate-safe retry and canceled training
      that does not register a candidate model.

## Documentation And Validation

- [ ] Update `docs/api/ml-api.md` training contract metadata and failure modes.
- [ ] Update `docs/operations/ml-operations.md` with retry/cancellation
      behavior and partial-artifact diagnostics.
- [ ] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [ ] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [ ] Run `npm run openspec:validate`.
