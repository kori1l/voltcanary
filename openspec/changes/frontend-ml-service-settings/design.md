## Context

ML-service now returns deterministic prediction metadata: model name, version,
type, status, prediction status, scores, signals, explanations, recommended
actions, and input hash. Analytics-service persists recent prediction metadata,
but the frontend currently has no dedicated workflow for viewing ML readiness or
configuring prediction preferences.

The settings experience affects cross-service behavior. User-facing preferences
must be durable and auditable, while model identity and prediction traces remain
read-only outputs of ML-service and analytics-service.

## Goals / Non-Goals

**Goals:**

- Provide a frontend settings workflow for ML prediction preferences.
- Make ML readiness and degraded states visible without requiring direct
  service diagnostics.
- Define what users can configure versus what is read-only operational status.
- Preserve analytics-service resilience when ML-service is unavailable.
- Keep settings compatible with account-level defaults and per-device overrides.

**Non-Goals:**

- Do not add model training, model upload, or model selection from arbitrary
  artifacts.
- Do not expose internal ML-service URLs or stack traces in the frontend.
- Do not make telemetry ingestion dependent on frontend ML settings.
- Do not store authoritative ML settings only in browser local storage.
- Do not redesign the broader dashboard or analytics UI.

## Decisions

### Durable settings live behind backend APIs

ML prediction settings SHALL be persisted through backend APIs rather than
browser-only state. The frontend may cache responses for UX, but analytics jobs
must read settings from server-side state or a server-derived configuration
boundary.

Alternative considered: local-only frontend settings. Rejected because analytics
jobs run asynchronously and cannot depend on a specific browser session.

### Separate read-only ML status from editable preferences

The UI SHALL present model name, version, model type, model status, prediction
status, confidence, signals, explanations, recommended actions, and input hash
as read-only status. Editable preferences SHALL be limited to prediction enablement,
allowed prediction mode, confidence warning threshold, maintenance exclusion
behavior, and explanation visibility.

Alternative considered: allow operators to edit model name or version. Rejected
because those values are output metadata from ML-service and must stay auditable.

### Support account defaults and device overrides

The settings model SHALL distinguish account-level defaults from per-device
overrides. A device without an override SHALL inherit account defaults and the UI
SHALL show the effective value and its source.

Alternative considered: device-only settings. Rejected because users need a
single default policy for fleets.

### Frontend uses analytics-service as its primary boundary

The frontend SHALL use analytics-facing APIs for settings and latest prediction
metadata. Direct ML-service calls are reserved for internal diagnostics or
backend orchestration, not normal user-facing UI flows.

Alternative considered: frontend calls ML-service directly. Rejected because the
gateway, auth, tenant isolation, and user-safe error handling already belong at
backend service boundaries.

## Risks / Trade-offs

- Backend settings API does not exist yet -> Include explicit backend/API tasks
  before frontend implementation.
- Too many knobs can confuse users -> Limit editable settings to operational
  policy choices and keep model metadata read-only.
- Account defaults and device overrides can be hard to reason about -> Show
  inherited versus overridden values in the UI.
- ML-service unavailable during page load -> Render degraded status from
  persisted analytics metadata and show retry/error states without breaking the
  settings form.

## Migration Plan

1. Add backend settings contract and persistence before wiring frontend saves.
2. Add frontend API client and typed DTOs.
3. Add settings page/panel behind existing authenticated app navigation.
4. Surface read-only latest prediction metadata alongside editable preferences.
5. Add frontend tests and update verification docs.

Rollback: keep frontend route hidden or disabled if backend settings APIs are not
available; existing analytics and prediction endpoints continue to work.

## Open Questions

- Should ML settings be account-scoped only for the first iteration, with device
  overrides deferred?
- Which roles may edit ML settings: account owner/admin only, or all users with
  device write access?
- Should confidence thresholds be global percentages or named presets such as
  strict, balanced, and permissive?
