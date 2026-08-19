# Tasks: Decompose ML-Service Runtime Components

## 1. Training Runtime

- [ ] 1.1 Extract run coordination, idempotency, timeout, and cancellation checkpoint behavior.
- [ ] 1.2 Extract anomaly-detection training runner.
- [ ] 1.3 Extract failure-prediction training runner.
- [ ] 1.4 Extract model card and approval gate assembly.
- [ ] 1.5 Extract artifact publishing and manifest verification.

## 2. Inference Runtime

- [ ] 2.1 Extract baseline heuristic scoring.
- [ ] 2.2 Extract trained model resolution and serving cache interaction.
- [ ] 2.3 Extract anomaly and failure model scorers.
- [ ] 2.4 Extract unavailable, insufficient-data, and feature-error response assembly.

## 3. Artifact Storage

- [ ] 3.1 Split local and S3 artifact provider implementations behind a common facade or protocol.
- [ ] 3.2 Extract bundle verification and hash helpers.
- [ ] 3.3 Extract cleanup preview/delete planning from provider I/O.
- [ ] 3.4 Keep `/health/artifacts` behavior compatible.

## 4. Settings And Tests

- [ ] 4.1 Replace direct global `settings` mutation in tests with fixture-scoped settings or explicit settings providers.
- [ ] 4.2 Split `test_ml_lifecycle.py` into focused training, inference, lifecycle, and approval-gate test modules.
- [ ] 4.3 Keep FastAPI route compatibility tests for `/train`, `/runs/{id}`, `/inference`, `/models`, and `/health/artifacts`.

## 5. Verification

- [ ] 5.1 Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [ ] 5.2 Run the ML production smoke when artifact/runtime behavior changes.
- [ ] 5.3 Run `npm run openspec:validate`.
