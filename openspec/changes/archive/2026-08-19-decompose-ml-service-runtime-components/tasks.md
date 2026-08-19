# Tasks: Decompose ML-Service Runtime Components

## 1. Training Runtime

- [x] 1.1 Extract run coordination, idempotency, timeout, and cancellation checkpoint behavior.
- [x] 1.2 Extract anomaly-detection training runner.
- [x] 1.3 Extract failure-prediction training runner.
- [x] 1.4 Extract model card and approval gate assembly.
- [x] 1.5 Extract artifact publishing and manifest verification.

## 2. Inference Runtime

- [x] 2.1 Extract baseline heuristic scoring.
- [x] 2.2 Extract trained model resolution and serving cache interaction.
- [x] 2.3 Extract anomaly and failure model scorers.
- [x] 2.4 Extract unavailable, insufficient-data, and feature-error response assembly.

## 3. Artifact Storage

- [x] 3.1 Split local and S3 artifact provider implementations behind a common facade or protocol.
- [x] 3.2 Extract bundle verification and hash helpers.
- [x] 3.3 Extract cleanup preview/delete planning from provider I/O.
- [x] 3.4 Keep `/health/artifacts` behavior compatible.

## 4. Settings And Tests

- [x] 4.1 Replace direct global `settings` mutation in tests with fixture-scoped settings or explicit settings providers.
- [x] 4.2 Split `test_ml_lifecycle.py` into focused training, inference, lifecycle, and approval-gate test modules.
- [x] 4.3 Keep FastAPI route compatibility tests for `/train`, `/runs/{id}`, `/inference`, `/models`, and `/health/artifacts`.

## 5. Verification

- [x] 5.1 Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [x] 5.2 Run the ML production smoke when artifact/runtime behavior changes.
- [x] 5.3 Run `npm run openspec:validate`.
