---
name: voltcanary-frontend
description: VoltCanary React TypeScript frontend workflow for the Vite web console, routing, pages, API clients, ML settings/workflows, device telemetry views, maintenance calendar, tests, styling, and builds. Use when Codex modifies or reviews files under frontend/ or frontend-facing API workflow contracts.
---

# VoltCanary Frontend

## Before Editing

Use `$voltcanary-openspec` first for frontend workflows backed by backend or ML
behavior. Read API contracts in `docs/api/*.md` and active OpenSpec specs before
changing request/response assumptions.

Start from these files:

- Routing: `frontend/src/app/App.tsx`
- Shell/layout: `frontend/src/app/AppLayout.tsx`
- Shared API client: `frontend/src/api/client.ts`
- Domain API modules: `frontend/src/*/*Api.ts`
- Styling: `frontend/src/app/styles.css`
- Tests: colocated `*.test.tsx` and `frontend/src/test/setup.ts`

## UI Direction

Keep the app as an operational console: dense, scannable, restrained, and
workflow-first. Preserve existing component and CSS conventions unless a task
explicitly asks for a redesign.

Use familiar controls for operational tasks: tabs for views, toggles for binary
state, inputs/sliders for numeric values, menus for option sets, and concise
icon/text buttons for clear commands. Avoid marketing-page patterns inside the
console.

Ensure responsive layouts have stable dimensions for tables, toolbars, charts,
status badges, and repeated cards so loading/error/data states do not shift or
overlap.

## Implementation Notes

Keep API access in the existing domain API modules instead of calling `fetch`
from pages. Preserve auth and error behavior from `frontend/src/api/client.ts`.

When a page visualizes ML, telemetry, alerts, maintenance, or device behavior,
show status and uncertainty explicitly instead of implying exact diagnostics.

For ML Settings feature-snapshot and training workflows, keep failure-prediction
snapshot inputs explicit in the UI (`modelFamily` and positive `horizonDays`) and
cover the submitted payload in tests. Training flows should reject snapshot
family or horizon mismatches before submitting the run.

For the ML Dataset Workbench, render selectable metrics only from the backend
preview response. Do not reintroduce device-metadata/default metric fallbacks.
Changes to scope, metrics, range, model family, horizon, split, feature config,
or quality gates must mark preview stale and keep create disabled until a fresh
preview succeeds. Keep out-of-order preview response guards covered by tests
when changing the workbench state flow.

## Validation

From `frontend/`:

```powershell
npm run typecheck
npm run test
npm run build
```

For visual or routing changes, run `npm run dev` and verify the affected
screen in the browser when feasible.
