# Gate ML Production Smoke In CI

## Why

VoltCanary has a documented Docker smoke path for the full failure-prediction
lifecycle, but the production-readiness path is not yet a required CI or agent
gate. As the ML module grows, unit tests can pass while the integrated lifecycle
regresses across dataset materialization, feature generation, labels, training,
artifact storage, deployment, live inference, and metrics.

The production smoke flow should become a repeatable automated gate for ML
runtime changes.

## What Changes

- Add a CI-friendly smoke command or workflow for the Docker ML production path.
- Run it for changes that affect ML service, analytics ML lifecycle, deployment
  wiring, feature contracts, model registry, or prediction traceability.
- Publish a compact verification artifact or log summary.
- Document when the smoke is required, how to run it locally, and how to triage
  failures.
- Keep narrow unit tests as the first validation layer, with the smoke as the
  integration gate.

## Impact

Affected specs:

- `ml-failure-prediction`

Affected implementation areas:

- `docs/flows/ml-failure-prediction-docker-verification.md`
- `scripts/` smoke automation
- CI workflow/configuration
- `deploy/docker-compose/`
- `deploy/k8s/` only if the smoke also covers local Kubernetes later
- `AGENTS.md` or project skills only if validation baselines change

Compatibility:

- Does not change runtime API behavior.
- Adds validation requirements for ML-affecting changes.
