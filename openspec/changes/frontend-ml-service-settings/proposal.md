## Why

VoltCanary now has meaningful ML prediction metadata, but operators have no
frontend workflow to inspect ML readiness, understand which prediction mode is
active, or configure safe prediction preferences. This creates a usability gap:
ML behavior exists, but users cannot see or tune it from the product.

## What Changes

- Add a frontend ML settings experience for viewing ML-service readiness,
  model registry metadata, prediction mode, fallback state, and recent
  prediction traceability.
- Define configurable settings for ML prediction behavior:
  - enable or disable ML-assisted predictions per device or account scope;
  - choose allowed prediction modes such as trained model only, baseline
    heuristic allowed, or rule-based fallback only;
  - configure confidence warning thresholds used by UI explanation states;
  - configure whether maintenance exclusions should suppress ML calls or only
    reduce confidence;
  - configure prediction explanation visibility for operational users.
- Keep model identity, model readiness, last prediction status, and input hash
  read-only in the UI.
- Define required backend/API support where frontend settings cannot be safely
  stored only in the browser.
- Add documentation and verification coverage for normal ML-ready, ML
  unavailable, insufficient-data, and maintenance-excluded states.

## Capabilities

### New Capabilities

- `frontend-ml-service-settings`: Frontend workflow and API expectations for
  configuring ML prediction preferences and displaying ML readiness/status.

### Modified Capabilities

- `ml-predictive-analytics`: Prediction metadata must be consumable by the
  frontend settings and explanation workflow.

## Impact

- `frontend/src/**` analytics, settings, routing, and API client code.
- Backend analytics or configuration APIs needed to persist ML prediction
  settings.
- `docs/api/analytics-api.md` and possibly a new settings API section.
- `docs/api/ml-api.md` for read-only model readiness/status display semantics.
- `docs/flows/system-verification-flow.md` for manual verification of ML
  settings and degraded states.
- Frontend tests for settings forms, read-only status panels, fallback badges,
  and API error handling.
