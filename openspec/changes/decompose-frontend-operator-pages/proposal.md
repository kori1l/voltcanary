# Change Proposal: Decompose Frontend Operator Pages

## Why

The frontend has page components that have grown into multi-workflow
orchestrators:

- `DeviceDetailsPage.tsx` is roughly 2900 lines and combines device loading,
  telemetry charts, analytics recalculation, maintenance, alert actions,
  registry/data-source/sensor management, simulation YAML editing, localStorage
  preferences, chart rendering, and derived insight text.
- `MlSettingsPage.tsx` is roughly 2300 lines and combines account/device ML
  settings, dataset workbench integration, imports, labels, async label jobs,
  training, model registry, deployment policies, monitoring, localStorage
  drafts, confirmation prompts, and formatting helpers.
- `MaintenanceCalendarPage.tsx`, `AlertsPage.tsx`, and `DevicesPage.tsx` are
  also large enough that new workflow behavior should not be appended without
  extraction.

This creates the same architectural problem as oversized backend services:
state management, API orchestration, domain workflow rules, rendering, and
formatting are hard to test in isolation.

## What Changes

- Split the operator pages into feature-level hooks, components, model helpers,
  and narrow page shells.
- Keep routes, existing visible workflows, and API modules compatible.
- Move localStorage-backed drafts/preferences behind typed hooks.
- Move prompt/confirmation and lifecycle action flows into reusable workflow
  helpers or components.
- Add tests for extracted hooks/helpers and keep existing page-level workflow
  tests as integration coverage.

## Non-Goals

- Do not redesign the visual language in this change.
- Do not change backend or ML contracts.
- Do not remove existing operator workflows.
- Do not make a new landing page or marketing surface.

## Impacted Areas

- `frontend/src/pages/DeviceDetailsPage.tsx`
- `frontend/src/pages/MlSettingsPage.tsx`
- `frontend/src/maintenance/MaintenanceCalendarPage.tsx`
- `frontend/src/pages/AlertsPage.tsx`
- `frontend/src/pages/DevicesPage.tsx`
- `frontend/src/features/**`
- `frontend/src/*/*Api.ts`
- `frontend/src/pages/*.test.tsx`
- `docs/frontend-redesign-audit.md`
- `openspec/specs/ml-console-workflows/spec.md`
- `openspec/specs/frontend-operator-console/spec.md`

## Open Questions

- Should simulation controls move to a dedicated dev/demo tools route in the
  first implementation pass, or stay under device details behind an extracted
  component until product direction is decided?
- Should ML Settings split by route, by tab component, or by feature package
  while preserving the current `/settings/ml` entry point?
