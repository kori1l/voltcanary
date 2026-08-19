# Tasks

## Contract And ML Service

- [x] Extend ML training request/response schemas with `trainingRunId`,
      `idempotencyKey`, and deterministic artifact target metadata.
- [x] Implement duplicate-safe ML-service training behavior for repeated
      requests with the same idempotency key.
- [x] Reject conflicting retries for the same idempotency key.
- [x] Add staged artifact persistence or partial-artifact cleanup.
- [x] Add Python-side cancellation checkpoints around major training phases.
- [x] Add configurable training timeout/resource limits.
- [x] Clarify or replace `/runs/{id}` so production status does not depend on
      process memory.
- [x] Add ML-service tests for retry, conflict, cancellation, timeout, and
      partial artifact cleanup.

## Analytics Service

- [x] Pass analytics `trainingRunId` and idempotency metadata to ML-service.
- [x] Update worker retry behavior to reuse the same idempotency key when
      retrying the same claimed run.
- [x] Update cancellation flow to signal or expose cancellation to ML-service
      checkpoints.
- [x] Add analytics-service tests for duplicate-safe retry and canceled training
      that does not register a candidate model.

## Documentation And Validation

- [x] Update `docs/api/ml-api.md` training contract metadata and failure modes.
- [x] Update `docs/operations/ml-operations.md` with retry/cancellation
      behavior and partial-artifact diagnostics.
- [x] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [x] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [x] Run `npm run openspec:validate`.
