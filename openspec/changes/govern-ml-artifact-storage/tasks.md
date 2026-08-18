# Tasks

## ML Service And Repository Policy

- [ ] Add explicit runtime/profile settings that forbid local artifact storage
      in production-like profiles.
- [ ] Update artifact health checks to fail readiness when production storage
      policy is violated.
- [ ] Move or mark any required model fixtures outside the production artifact
      registry path.
- [ ] Update ignore rules and tests so new generated model artifacts are not
      committed from `ml-service/models/`.
- [ ] Add tests for local/test storage mode, production local-mode rejection,
      and S3 readiness failure messages.

## Artifact Lifecycle

- [ ] Define a referenced-artifact lookup using training runs, model versions,
      deployment policies, prediction traces, and audit needs.
- [ ] Add a dry-run cleanup/reporting path for unreferenced local/S3 artifacts.
- [ ] Block cleanup of active, policy-referenced, prediction-referenced, or
      audit-required artifacts.

## Documentation And Validation

- [ ] Update `docs/operations/ml-operations.md` with production storage mode,
      readiness diagnostics, and artifact cleanup policy.
- [ ] Update `docs/api/ml-api.md` if `/health/artifacts` response details
      change.
- [ ] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [ ] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [ ] Run `npm run openspec:validate`.
