## Context

Current Java training code produces synthetic artifact URIs. Current Python
training can write local JSON artifacts, but analytics-service does not call
that path for production training and live inference cannot resolve Java
`modelVersionId` values in Python.

The registry must become an operational boundary, not a UI metadata table.

## Goals

- Train at least one real model from a materialized feature artifact.
- Store model artifacts durably with hashes.
- Load models by `modelVersionId` during inference.
- Keep baseline heuristic as fallback.
- Make training status transitions reflect actual worker progress.

## Non-Goals

- Do not implement supervised failure prediction in this change.
- Do not remove existing `/inference`, `/models`, or `/train` compatibility
  routes unless a versioned replacement is added.
- Do not allow arbitrary executable model uploads.

## Design

### Artifact Store

Use a single artifact abstraction for:

- dataset artifacts;
- feature artifacts;
- model artifact bundles;
- metrics reports;
- model cards;
- inference smoke-test fixtures.

MinIO/S3 should be the deployed backing store. Local filesystem may remain only
for tests or local fallback, clearly marked as non-production.

### Model Artifact Manifest

Each model artifact bundle should include a manifest with:

- `modelVersionId`
- model family and algorithm;
- feature schema hash;
- feature config/version;
- training run id;
- dataset version id;
- dependency/runtime versions;
- artifact files and hashes;
- supported metrics and device types;
- inference input contract.

### Worker Runtime

Training execution should move to a real worker path. Acceptable designs:

- analytics-service owns run state and calls ML-service/worker;
- ML-worker owns execution and updates analytics-service through internal APIs;
- ML-service includes a worker process separate from the API process.

Whichever design is chosen, candidate model registration must occur only after:

1. feature artifact can be loaded;
2. model training succeeds;
3. metrics are computed;
4. artifacts are stored and hash-verified;
5. saved artifact can be loaded for inference.

### Model Identity

`modelVersionId` should be the canonical runtime identifier. ML-service should
resolve it to artifact metadata, load the bundle, and return the same id in
prediction traceability.

### Job Control

Training runs should have:

- lease owner;
- lease expiration;
- heartbeat timestamp;
- cancellation requested flag;
- retry count;
- stale-run recovery.

## Risks

- Running heavy training inside a web API process can hurt latency. Prefer a
  worker process even if it shares code with ML-service.
- Artifact and registry state can diverge. Approval and inference must verify
  artifact existence and hash.
- Supporting both old local artifacts and new object storage requires explicit
  environment/profile behavior.
