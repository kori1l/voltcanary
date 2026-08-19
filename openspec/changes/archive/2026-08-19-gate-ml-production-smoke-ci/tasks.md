# Tasks

## Smoke Automation

- [x] Review the existing Docker ML failure-prediction verification flow and
      extract a CI-safe command path.
- [x] Add bounded polling and clear failure diagnostics for each lifecycle
      step.
- [x] Assert selected model id, artifact hash, feature hash, input hash, policy
      source, fallback reason when applicable, and failure-risk fields.
- [x] Assert monitoring signals for fallback, errors, canary/shadow, and
      quality outcomes when labels exist.
- [x] Prevent smoke logs from exposing secrets or full model artifacts.

## CI And Documentation

- [x] Add path-filtered CI wiring or a documented required manual CI job for
      ML-affecting changes.
- [x] Publish a compact smoke result artifact/log summary.
- [x] Update `docs/flows/ml-failure-prediction-docker-verification.md` with CI
      mode and triage guidance.
- [x] Update `docs/operations/ml-operations.md` with when the smoke is required.
- [x] Update `AGENTS.md` or project skills only if the standard validation
      baseline changes.

## Validation

- [x] Run the CI-safe smoke locally against Docker profile.
- [x] Run `npm run openspec:validate`.
- [x] Run narrow unit tests for any scripts or helpers added.
