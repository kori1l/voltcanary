## 1. Artifact Store

- [x] 1.1 Define model artifact manifest format.
- [x] 1.2 Implement durable artifact storage for ML-service.
- [x] 1.3 Store artifact hashes, sizes, and manifest URI in model registry metadata.
- [x] 1.4 Add artifact existence/hash verification before model approval.
- [x] 1.5 Add artifact round-trip tests.

## 2. Python Runtime

- [x] 2.1 Add production ML dependencies for tabular model training and serialization.
- [x] 2.2 Implement feature artifact loading.
- [x] 2.3 Implement at least one real anomaly detection training pipeline.
- [x] 2.4 Serialize preprocessing and model artifacts.
- [x] 2.5 Add inference loading by `modelVersionId`.

## 3. Training Orchestration

- [x] 3.1 Decide and document whether analytics-service or ML-worker owns run state updates.
- [x] 3.2 Replace synthetic Java training success with real worker execution or delegation.
- [x] 3.3 Register candidate models only after artifact load-test inference passes.
- [x] 3.4 Add worker leases, heartbeats, cancellation checks, and stale-run recovery.
- [x] 3.5 Add tests for failed artifact creation, cancellation, and retry.

## 4. Registry And Inference Identity

- [x] 4.1 Make `modelVersionId` the canonical cross-service model identifier.
- [x] 4.2 Update ML inference request/response to include `modelVersionId` additively.
- [x] 4.3 Stop relying on Python in-memory registry in production paths.
- [x] 4.4 Add contract tests proving an approved model can be loaded and scored by id.

## 5. Deployment And Docs

- [x] 5.1 Configure MinIO/S3 artifact storage for local Docker and deployed environments.
- [x] 5.2 Add worker service or process configuration.
- [x] 5.3 Update ML operations docs for training, artifacts, and rollback.
- [x] 5.4 Add health checks for artifact store connectivity.

## 6. Verification

- [x] 6.1 Run ML-service tests.
- [x] 6.2 Run analytics-service ML training lifecycle tests.
- [x] 6.3 Run Docker/local smoke test for model artifact creation and loading.
- [x] 6.4 Run `npm run openspec:validate`.
