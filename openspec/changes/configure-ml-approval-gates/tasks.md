# Tasks

## ML Service

- [ ] Add approval gate profile schema/config with non-trivial default
      thresholds.
- [ ] Pass resolved gate thresholds into failure-prediction approval metadata.
- [ ] Store profile id/version, observed metrics, thresholds, and pass/fail
      details in manifest, `model.json`, and `model-card.json`.
- [ ] Add tests for passing gates, failing gates, missing metric behavior, and
      profile metadata persistence.

## Backend And Frontend

- [ ] Add analytics-service approval validation that rejects candidates with
      missing required gates or failed required gates.
- [ ] Include approval profile metadata in model registry DTOs where needed.
- [ ] Update model cards to show threshold values, observed metrics, and
      pass/fail reasons.
- [ ] Align frontend Approve button state with backend approval behavior.
- [ ] Add backend and frontend tests for blocked approval and displayed gate
      reasons.

## Documentation And Validation

- [ ] Update `docs/api/ml-api.md` with approval profile metadata.
- [ ] Update `docs/operations/ml-operations.md` with recommended starting
      thresholds and promotion guidance.
- [ ] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [ ] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [ ] Run `cd frontend; npm run typecheck; npm run test`.
- [ ] Run `npm run openspec:validate`.
