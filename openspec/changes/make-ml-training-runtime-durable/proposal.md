# Make ML Training Runtime Durable

## Why

Analytics-service owns durable training run state, leases, cancellation, and
candidate registration. ML-service training, however, is still a synchronous
`/train` operation with in-memory run lookup and no Python-side idempotency or
checkpointed cancellation. That creates risk for large training jobs, retries,
worker restarts, and duplicate artifact writes.

The ML runtime needs a job-aware training contract that remains compatible with
analytics-service ownership while allowing Python training to be bounded,
idempotent, cancelable, and observable.

## What Changes

- Include analytics-owned `trainingRunId` and idempotency metadata in ML-service
  training requests.
- Make ML-service training execution idempotent for retries of the same run and
  artifact target.
- Add Python-side cancellation checkpoints and resource/time limits.
- Preserve or replace `/runs/{id}` with a durable lookup contract that does not
  depend on process memory in production.
- Add explicit timeout and partial-artifact cleanup behavior.
- Update worker tests and ML-service tests for retries, cancellation, and
  duplicate submission.

## Impact

Affected specs:

- `ml-artifact-runtime`

Affected implementation areas:

- `ml-service/app/api/training.py`
- `ml-service/app/services/training_service.py`
- `ml-service/app/schemas/training.py`
- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/service/MlTrainingWorker.java`
- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/ml/MlTrainingRequest.java`
- analytics-service tests
- `docs/api/ml-api.md`
- `docs/operations/ml-operations.md`

Compatibility:

- Existing `/train` can remain as the route, but request/response metadata
  becomes richer.
- Analytics-service remains the source of truth for operator-visible training
  run state.
