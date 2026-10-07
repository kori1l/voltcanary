# Make console loading and data states truthful

## Why

Dashboard and Alerts report safe-looking zeros when dependencies fail, while missing telemetry or prediction can look like healthy status or an actual zero-valued measurement.

Source: [UI design audit](../../../../docs/target/ui-design-requirements.md).
Coverage: UI-02, UI-03, O07, O08, F06. This is planned work; no implementation or verification is claimed complete.

## What Changes

- Explicit independently loaded states: Data-backed console sections SHALL distinguish loading, successful-empty, filtered-empty, error, stale retained data and insufficient evidence. Failed dependencies SHALL not replace independently loaded sections with invented zeros.
- Unknown is not healthy or zero: Missing telemetry, risk, prediction and event coverage SHALL render as neutral unknown or insufficient-evidence states rather than healthy color, zero risk, zero percentage or a zero-valued chart.
- Freshness and retry preserve operator context: Refreshing or retrying SHALL preserve valid filters, drafts, selected entities and last successful data while marking its freshness. A successful empty result SHALL explain first-use versus no matching filters with the appropriate action.
- Useful fleet dashboard: The dashboard SHALL prioritize actionable devices and alerts with freshness, evidence and a direct next action; registry ACTIVE SHALL not by itself imply an online telemetry connection.

## Impact

- Added capability: `console-data-state-semantics`.
- Implementation owners: frontend/src/pages/DashboardPage.tsx, focused device/telemetry/alerts/ML loading hooks and view models.
- Prerequisites: `establish-console-design-system`.
- Existing APIs, authorization and domain invariants remain authoritative. No breaking API, data migration, token-policy change or UI translation is proposed.
- Follow existing frontend-operator-console decomposition and typed API/storage rules. If implementation needs an additional API contract, document its exact compatible scope before changing it.
- Validation includes focused tests and browser evidence, frontend typecheck/test/build, OpenSpec validation and aligned current documentation. Unobserved audit states require populated fixtures.


## Completion

Implemented and archived on 2026-10-07. See [verification evidence](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06) and the completed task checklist. Original proposal context above records the planned scope.
