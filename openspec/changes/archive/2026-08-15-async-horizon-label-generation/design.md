# Async Horizon Label Generation Design

## Current Problem

`POST /analytics/ml/datasets/horizon-labels` performs label planning,
persistence, summary calculation, and response construction within a single
gateway-routed HTTP request. The gateway response timeout is shorter than worst
case label generation time, so valid requests can surface as `504 Gateway
Timeout` in the console.

The UI cannot reliably distinguish "work failed" from "request timed out while
work may still be running" because there is no durable job id or status endpoint.

## Proposed Contract

Start endpoint:

- `POST /analytics/ml/datasets/horizon-labels`
- Auth/write boundary remains unchanged.
- Request fields remain the existing generation fields:
  `datasetVersionId`, `horizonDays`, `generationVersion`,
  `includePreventiveMaintenance`.
- Response returns HTTP `202 Accepted` for async start:
  `jobId`, `datasetVersionId`, `status`, `submittedAt`, `startedAt`,
  `completedAt`, `progress`, `message`, and optional `result`.

Status endpoint:

- `GET /analytics/ml/datasets/horizon-labels/{jobId}`
- Returns the same job status envelope.
- `result` is present only when status is `COMPLETED`.
- `failureReason` is present only when status is `FAILED` and must be sanitized.

Job states:

- `QUEUED`: accepted and waiting to execute.
- `RUNNING`: label generation is executing.
- `COMPLETED`: labels were persisted and summary/lineage is available.
- `FAILED`: generation failed and can be retried.

## Persistence

Analytics-service should persist generation jobs in its own database. The record
should include:

- account id, dataset version id, submitted by user id when available;
- horizon days, generation version, include preventive maintenance;
- input lineage/config hash used for idempotency;
- status timestamps and sanitized failure reason;
- generated counts and lineage hash after completion.

The idempotency key should be deterministic from account id, dataset version id,
generation config, and input lineage hash. A repeated request should return the
existing active/completed job instead of starting duplicate generation.

## Execution

The controller should validate authorization and request shape, then delegate to
a service that creates or reuses a job. Execution may use an internal executor
inside analytics-service for the first implementation. The design should keep
the use case isolated so it can later move to a queue without changing public
HTTP contracts.

Generated labels must remain deterministic and auditable. Existing label
lineage hashes must not change except when inputs or generation config change.

## Frontend Flow

The Labels tab should:

- show `QUEUED/RUNNING/COMPLETED/FAILED` status for horizon label generation;
- poll the status endpoint while a job is active, with a bounded interval;
- provide a manual "Check status" action;
- refresh `listMlLabels(datasetVersionId)` after completion;
- show final generated/positive/normal/unobservable counts and lineage hash;
- avoid displaying raw gateway timeout messages for normal long-running work.

## Failure Modes

- If job start fails validation, show the validation error and do not create an
  active status.
- If status polling fails temporarily, keep the last known job status and show a
  non-blocking warning.
- If the job fails, show `FAILED` with sanitized reason and allow retry.
- If the frontend is refreshed, it should be able to recover the latest active
  or completed job for the selected dataset/config either from returned job id
  state or a documented lookup behavior.

## Open Questions

- Whether job execution should be backed by an in-process executor initially or
  a queue topic from the first implementation.
- Whether to keep a temporary synchronous `200 OK` response for very small jobs
  or always return `202 Accepted`.
