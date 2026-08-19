# Tasks

## ML Service

- [x] Add approval gate profile schema/config with non-trivial default
      thresholds.
- [x] Pass resolved gate thresholds into failure-prediction approval metadata.
- [x] Store profile id/version, observed metrics, thresholds, and pass/fail
      details in manifest, `model.json`, and `model-card.json`.
- [x] Add tests for passing gates, failing gates, missing metric behavior, and
      profile metadata persistence.

## Backend And Frontend

- [x] Add analytics-service approval validation that rejects candidates with
      missing required gates or failed required gates.
- [x] Include approval profile metadata in model registry DTOs where needed.
- [x] Update model cards to show threshold values, observed metrics, and
      pass/fail reasons.
- [x] Align frontend Approve button state with backend approval behavior.
- [x] Add backend and frontend tests for blocked approval and displayed gate
      reasons.

## Documentation And Validation

- [x] Update `docs/api/ml-api.md` with approval profile metadata.
- [x] Update `docs/operations/ml-operations.md` with recommended starting
      thresholds and promotion guidance.
- [x] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [x] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [x] Run `cd frontend; npm run typecheck; npm run test`.
- [x] Run `npm run openspec:validate`.
