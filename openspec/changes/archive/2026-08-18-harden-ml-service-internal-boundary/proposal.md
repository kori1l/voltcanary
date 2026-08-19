# Harden ML Service Internal Boundary

## Why

Analytics-service already sends `X-Source-Service` and
`X-Internal-Service-Token` headers when calling ML-service, but ML-service does
not currently enforce that boundary. In a production cluster, `/train` and
`/inference` can perform expensive model work and expose artifact metadata, so
they need an explicit internal-service authorization and input safety boundary.

## What Changes

- Add ML-service middleware or dependencies that enforce internal service
  authentication for non-health ML endpoints in production-like profiles.
- Keep liveness and readiness probes compatible: `/health` and
  `/health/artifacts` remain usable by Kubernetes probes.
- Add request body size and shape guards for `/train` and `/inference`.
- Return sanitized `401/403/413/400` responses without stack traces or artifact
  details.
- Add deployment config that makes auth enforcement explicit for local, test,
  Docker, and Kubernetes profiles.
- Update API/operations docs and tests for the authenticated internal boundary.

## Impact

Affected specs:

- `ml-artifact-runtime`

Affected implementation areas:

- `ml-service/app/main.py`
- `ml-service/app/api/*`
- `ml-service/app/config/settings.py`
- `ml-service/app/tests/`
- `backend/services/analytics-service/src/main/java/com/voltcanary/analytics/ml/MlClient.java`
- `deploy/k8s/base/config.yaml`
- `deploy/k8s/base/apps.yaml`
- `docs/api/ml-api.md`
- `docs/operations/ml-operations.md`

Compatibility:

- Existing backend callers remain compatible when configured with the same
  internal token already sent by `MlClient`.
- Local development can keep auth disabled only through an explicit local/test
  setting.
