# Harden ML Failure Prediction Production Readiness

## Why

`docs/ml-failure-prediction-production-audit.md` was written before the recent
ML lifecycle, feature, artifact, supervised failure prediction, and deployment
runtime changes were completed. The repository now implements many of the
audit's critical blockers, but the audit has no status traceability and the
remaining production-readiness work is not captured as an active OpenSpec
change.

The current ML Settings frontend also exposes production workflows with cramped
inline forms and raw API error JSON. That makes the data-engineer workflow hard
to use even when the backend contracts are present.

## What Changes

- Add an audit status pass that maps each major production blocker to
  implemented evidence or remaining work.
- Add production-readiness verification requirements for a complete failure
  prediction lifecycle, not only per-service metadata tests.
- Polish the ML Settings frontend forms so datasets, labels, training, models,
  policies, and monitoring controls are readable and responsive.
- Normalize ML frontend error display so raw JSON responses do not leak into
  operator-facing banners.

## Impact

- Scope: analytics-service, ml-service, frontend, docs, and OpenSpec only where
  production-readiness evidence is needed.
- Compatibility: UI-only polish should be additive. Verification and docs should
  not change API contracts unless a remaining audit gap requires it.
- Risk: medium. The main risk is confusing archived completed ML work with real
  remaining hardening work, so the first task is an explicit status matrix.
