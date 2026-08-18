# Tasks

## Smoke Automation

- [ ] Review the existing Docker ML failure-prediction verification flow and
      extract a CI-safe command path.
- [ ] Add bounded polling and clear failure diagnostics for each lifecycle
      step.
- [ ] Assert selected model id, artifact hash, feature hash, input hash, policy
      source, fallback reason when applicable, and failure-risk fields.
- [ ] Assert monitoring signals for fallback, errors, canary/shadow, and
      quality outcomes when labels exist.
- [ ] Prevent smoke logs from exposing secrets or full model artifacts.

## CI And Documentation

- [ ] Add path-filtered CI wiring or a documented required manual CI job for
      ML-affecting changes.
- [ ] Publish a compact smoke result artifact/log summary.
- [ ] Update `docs/flows/ml-failure-prediction-docker-verification.md` with CI
      mode and triage guidance.
- [ ] Update `docs/operations/ml-operations.md` with when the smoke is required.
- [ ] Update `AGENTS.md` or project skills only if the standard validation
      baseline changes.

## Validation

- [ ] Run the CI-safe smoke locally against Docker profile.
- [ ] Run `npm run openspec:validate`.
- [ ] Run narrow unit tests for any scripts or helpers added.
