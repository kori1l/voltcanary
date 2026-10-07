# Improve device overview, telemetry and analytics workflows

## Why

Overview repeats large monitoring blocks, no-data can look healthy, metric counts and empty charts disagree, and analysis/control context is scattered between tabs.

Source: [UI design audit](../../../../docs/target/ui-design-requirements.md).
Coverage: F11, F12, F13, F14. This is planned work; no implementation or verification is claimed complete.

## What Changes

- Actionable device overview: Overview SHALL prioritize current state, evidence, freshness and next action, reduce duplicated full-monitoring blocks and repeated commands, and render unknown health/risk neutrally.
- Consistent metric and range controls: Telemetry SHALL reconcile selected versus available metric counts, provide named metric search/selection and exact timezone-aware date fields, offer a range containing actual samples and explain missing charts.
- Evidence-preserving charts and analysis: Charts SHALL display units, missing-data gaps, freshness and shared event/range context. Analyze SHALL provide accessible alternatives to brush input and distinguish measurements, inferred risk, confidence and recommended checks.
- Focused analytics and alert actions: Analytics SHALL follow observation, risk/confidence and recommendation with technical lineage secondary. Recalculation SHALL expose job status and avoid duplicate primary commands; device Alerts SHALL share status/filter semantics and evidence links with global Inbox.

## Impact

- Added capability: `telemetry-console-experience`.
- Implementation owners: frontend/src/devices/details/, frontend/src/features/telemetry/, focused analytics and alert view models.
- Prerequisites: `establish-console-design-system`, `fix-console-data-state-semantics`.
- Existing APIs, authorization and domain invariants remain authoritative. No breaking API, data migration, token-policy change or UI translation is proposed.
- Follow existing frontend-operator-console decomposition and typed API/storage rules. If implementation needs an additional API contract, document its exact compatible scope before changing it.
- Validation includes focused tests and browser evidence, frontend typecheck/test/build, OpenSpec validation and aligned current documentation. Unobserved audit states require populated fixtures.


## Completion

Implemented and archived on 2026-10-07. See [verification evidence](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06) and the completed task checklist. Original proposal context above records the planned scope.
