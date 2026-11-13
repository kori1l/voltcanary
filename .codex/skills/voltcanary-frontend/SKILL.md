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

AI dashboard reviews must render the prepared definition through the shared
`DashboardDefinitionPreview` / `DashboardCanvas` / `DashboardWidgetPanel` path.
Do not generate a separate mock layout or fabricated readings. Chat refinements
go through server-owned widget composition preparation (legacy template tasks remain compatible); creation uses the immutable reviewed
definition. Keep the assistant in the application-level overlay, not device pages.
Clarification defaults come from structured server metadata, never question-text
matching or hardcoded frontend names. Task memory is owned by the server;
starting input cancels a pending default for the entire question.
Dashboard refinements use server-merged widget edits, preserving omitted fields
and stable widget keys. Review Back/Forward/version selection fetches immutable
authorized plans; it must not ask the model to approximate an earlier layout or
execute the selected review. Recovery gets review ancestry from the server. Vehicle source-field history supports
list and line presentation; boolean histories use recorded source observations,
not an invented numeric metric. Technical preparation errors are repaired server-side
with a bounded same-model retry; names and pending changes survive failed preparation.

Widget settings and palette visualization choices come from dashboard-owned manifest
capabilities. Keep title/header and evidence-detail visibility in General for every
widget; render supported presentation controls by catalogue section/group in Appearance.
Do not reintroduce a per-widget settings matrix in the frontend or AI prompts. Keep
legacy presentation intact and merge typed AI settings by key, including explicit resets.


## Implementation Notes

The /data native table workbench lives in features/native-data. Keep the route
page small; reuse nativeDataApi and server-generated /data/language contracts.
Native review IDs remain in the URL across reload. Unknown/lost responses must
inspect/reconcile the original review and receipt, never generate another effect
or key as recovery. Preserve exact numeric strings, immutable keys and the
Unchanged/NULL distinction. Browser tests use visual fixtures separately from
signed H2/PostgreSQL owner evidence; neither qualifies missing AI/rule/import gates.
Manual and assistant native-table reviews share NativeCollectionPreview. Keep
its authoring parity fixture aligned with common-vcl/native-authoring. An expired
UNKNOWN native review offers receipt inspection only; it cannot renew confirmation.

Assistant transcripts and chat selection are owned by the conversation APIs in
ai-agent-service. Use the shared server conversation hook in every window mode;
local storage holds only owner-scoped redacted unsent drafts and legacy recovery
pointers. History reads must not invoke the model, auto-answer or execute tools.

Keep API access in the existing domain API modules instead of calling `fetch`
from pages. Preserve auth and error behavior from `frontend/src/api/client.ts`.

Keep route pages as shells for route params, top-level data composition, and
layout. Do not append new independent workflows to large pages such as
`DeviceDetailsPage.tsx`, `MlSettingsPage.tsx`, `MaintenanceCalendarPage.tsx`,
`AlertsPage.tsx`, or `DevicesPage.tsx`; extract focused feature components,
hooks, and model helpers first. Wrap localStorage/sessionStorage usage in typed
helpers or hooks for drafts, chart preferences, source ids, and async job state.
Do not introduce generic `*ConfigService`, `*SettingsService`, or catch-all
settings modules for user-managed choices; name helpers for the business
decision they own, such as chart preferences, training drafts, deployment
policy, simulator profile state, or model selection.

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
