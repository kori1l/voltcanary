# Make ML Training Runtime Durable Design

## Ownership

Analytics-service remains the durable owner of:

- training run records;
- status transitions visible to operators;
- worker leases and heartbeats;
- candidate model registration;
- deployment lifecycle.

ML-service owns:

- fitting;
- model artifact serialization;
- artifact verification;
- Python-side cancellation checkpoints;
- artifact target idempotency.

## Training Request Contract

Training requests should include:

- `trainingRunId`;
- `idempotencyKey`;
- requested artifact bundle id or deterministic artifact prefix;
- dataset version id and feature snapshot id;
- model family, algorithm, horizon, hyperparameters;
- cancellation/timeout budget metadata when available.

If the same idempotency key is retried after a successful artifact write,
ML-service should return the same verified artifact metadata instead of writing
a duplicate bundle. If payload metadata differs for the same idempotency key,
ML-service should return a conflict.

## Cancellation And Timeouts

ML-service cannot rely only on Java worker cancellation. Training should check
for cancellation between major phases:

1. request validation;
2. feature loading;
3. target construction;
4. fitting;
5. evaluation;
6. artifact persistence;
7. verification.

The first implementation can pass a cancellation flag through a callback or
poll analytics-service. If cross-service polling is too heavy for the first
slice, add cooperative checkpoints around phases that can stop before candidate
registration and document the limitation.

## Partial Artifacts

If training fails or is canceled after writing partial files, ML-service should
either:

- write to a staging prefix and promote only after verification; or
- remove incomplete objects before returning failure.

Candidate registration must only see verified final artifact metadata.

## Durable Run Lookup

The Python in-memory `runs` map is acceptable for tests and local compatibility
but not production status. `/runs/{id}` should either proxy durable state from
analytics-service later or clearly return only ML-service-local execution
details. Production docs must not imply it is the source of truth.

## Verification

Tests should cover:

- retry with same idempotency key returns same artifact;
- retry with conflicting payload is rejected;
- cancellation before artifact promotion returns `CANCELED`;
- failure during artifact write leaves no final bundle;
- analytics worker handles duplicate-safe ML responses.
