# Tasks

## Feature Contract

- [x] Add or refactor shared feature contract metadata generation for offline
      snapshots and online inference.
- [x] Include generator version, template id/version, model family, horizon,
      required columns, and schema hash in compatibility checks.
- [x] Add explicit fallback/status reasons for contract version, template, and
      horizon mismatches.
- [x] Persist required/generated feature metadata in prediction traceability.

## Tests

- [x] Add backend parity fixtures comparing `MlFeaturePipelineService` output
      with `MlInferenceRequest` online feature vectors.
- [x] Add failure-prediction parity coverage with positive `horizonDays`.
- [x] Add tests for missing required columns, schema mismatch, template
      mismatch, and horizon mismatch.
- [x] Add ML-service tests proving incompatible feature metadata returns
      explicit unavailable or mismatch statuses.

## Documentation And Validation

- [x] Update `docs/api/ml-api.md` feature contract metadata and failure
      statuses.
- [x] Update `docs/operations/ml-operations.md` with feature parity diagnostics.
- [x] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [x] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [x] Run `npm run openspec:validate`.
