## Why

VoltCanary stores deployment policies, shadow mode, canary mode, and prediction
traceability, but runtime behavior is incomplete. Device-type policies are not
used in live recalculation when device type is unavailable, canary percentages
are not enforced, and monitoring is not enough for data engineers to compare
active, shadow, canary, fallback, and drift behavior.

Production ML needs deployment controls that are true at runtime and observable.

## What Changes

- Include device type and model compatibility in live policy resolution.
- Implement deterministic canary selection.
- Separate active, shadow, canary, fallback, and rule outputs in traceability.
- Remove or gate demo model seeding from production behavior.
- Add model-quality, drift, fallback, latency, and false-alert monitoring.
- Add frontend views for deployment comparison and operational health.

## Capabilities

### New Capabilities

- `ml-deployment-operations`: runtime-true deployment modes, policy resolution,
  production-safe model listing, and ML monitoring.

## Impact

- `backend/services/analytics-service/**`: policy resolution, canary selection,
  prediction traceability, metrics.
- `ml-service/**`: inference statuses and model-specific metrics.
- `backend/services/device-service/**`: device type metadata for analytics.
- `frontend/src/**`: monitoring and deployment comparison UI.
- `docs/api/**`, `docs/operations/**`, and observability config.

## Dependencies

Depends on `unify-ml-artifacts-runtime`. Monitoring of failure-specific quality
also depends on `supervised-failure-prediction`.
