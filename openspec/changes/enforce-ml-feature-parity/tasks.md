# Tasks

## Feature Contract

- [ ] Add or refactor shared feature contract metadata generation for offline
      snapshots and online inference.
- [ ] Include generator version, template id/version, model family, horizon,
      required columns, and schema hash in compatibility checks.
- [ ] Add explicit fallback/status reasons for contract version, template, and
      horizon mismatches.
- [ ] Persist required/generated feature metadata in prediction traceability.

## Tests

- [ ] Add backend parity fixtures comparing `MlFeaturePipelineService` output
      with `MlInferenceRequest` online feature vectors.
- [ ] Add failure-prediction parity coverage with positive `horizonDays`.
- [ ] Add tests for missing required columns, schema mismatch, template
      mismatch, and horizon mismatch.
- [ ] Add ML-service tests proving incompatible feature metadata returns
      explicit unavailable or mismatch statuses.

## Documentation And Validation

- [ ] Update `docs/api/ml-api.md` feature contract metadata and failure
      statuses.
- [ ] Update `docs/operations/ml-operations.md` with feature parity diagnostics.
- [ ] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [ ] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [ ] Run `npm run openspec:validate`.
