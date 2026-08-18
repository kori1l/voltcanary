# Tasks

## Backend

- [x] Add analytics-service persistence for horizon label generation jobs,
      including status, request config, idempotency hash, result summary, and
      sanitized failure reason.
- [x] Add DTOs for generation job start/status responses and status enum.
- [x] Change `POST /analytics/ml/datasets/horizon-labels` to create or reuse a
      job and return an async status envelope.
- [x] Add `GET /analytics/ml/datasets/horizon-labels/{jobId}` for status
      polling with account/authorization checks.
- [x] Execute generation outside the gateway request path while preserving
      deterministic label lineage and existing generated label semantics.
- [x] Add idempotency handling for repeated start requests with the same dataset
      version, generation config, and input lineage.
- [x] Add analytics-service tests for queued/running/completed/failed states,
      idempotent start, authorization, and retry after failure.

## Frontend

- [x] Update `mlModuleApi.ts` to use async start/status DTOs for horizon labels.
- [x] Update the Labels tab to show job status, poll active jobs, refresh labels
      on completion, and show final counts/lineage.
- [x] Replace timeout/error UI with workflow status for long-running generation.
- [x] Add frontend tests for start, polling, completion, failure, retry, and page
      refresh/recovery behavior.

## Documentation And Validation

- [x] Update ML API docs with start/status endpoint contracts and status states.
- [x] Run `npm run openspec:validate`.
- [x] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [x] Run `cd frontend; npm run typecheck; npm run test -- MlSettingsPage.test.tsx`.
