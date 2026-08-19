# Tasks

## ML Service And Repository Policy

- [x] Add explicit runtime/profile settings that forbid local artifact storage
      in production-like profiles.
- [x] Update artifact health checks to fail readiness when production storage
      policy is violated.
- [x] Move or mark any required model fixtures outside the production artifact
      registry path.
- [x] Update ignore rules and tests so new generated model artifacts are not
      committed from `ml-service/models/`.
- [x] Add tests for local/test storage mode, production local-mode rejection,
      and S3 readiness failure messages.

## Artifact Lifecycle

- [x] Define a referenced-artifact lookup using training runs, model versions,
      deployment policies, prediction traces, and audit needs.
- [x] Add a dry-run cleanup/reporting path for unreferenced local/S3 artifacts.
- [x] Block cleanup of active, policy-referenced, prediction-referenced, or
      audit-required artifacts.

## Documentation And Validation

- [x] Update `docs/operations/ml-operations.md` with production storage mode,
      readiness diagnostics, and artifact cleanup policy.
- [x] Update `docs/api/ml-api.md` if `/health/artifacts` response details
      change.
- [x] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [x] Run `cd backend; mvn -pl services/analytics-service -am test`.
- [x] Run `npm run openspec:validate`.
