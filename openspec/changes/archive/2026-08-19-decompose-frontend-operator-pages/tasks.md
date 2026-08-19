# Tasks: Decompose Frontend Operator Pages

## 1. Device Details

- [x] 1.1 Extract device/telemetry/analytics loading into typed hooks.
- [x] 1.2 Extract telemetry chart rendering and chart preference storage into feature modules.
- [x] 1.3 Extract maintenance window state and commands.
- [x] 1.4 Extract data-source, sensor, and analytics-config registry workflows.
- [x] 1.5 Extract simulator YAML parsing, editing, validation, and runtime commands.
- [x] 1.6 Keep `/devices/:deviceId` route behavior compatible.

## 2. ML Settings

- [x] 2.1 Extract ML workspace data loading and refresh orchestration.
- [x] 2.2 Extract lifecycle confirmation/impact action handling.
- [x] 2.3 Extract labels and async horizon generation state.
- [x] 2.4 Extract training draft/prerequisite validation.
- [x] 2.5 Extract model registry, deployment policy, and monitoring tabs into feature modules.
- [x] 2.6 Keep `/settings/ml` route behavior compatible.

## 3. Secondary Large Pages

- [x] 3.1 Audit `MaintenanceCalendarPage.tsx`, `AlertsPage.tsx`, and `DevicesPage.tsx` before adding new workflow behavior.
- [x] 3.2 Extract feature hooks/components first when a new change touches one of those pages.

## 4. Tests And Verification

- [x] 4.1 Add unit tests for extracted hooks and pure workflow helpers.
- [x] 4.2 Keep or add page-level tests for critical ML and device investigation workflows.
- [x] 4.3 Run `cd frontend; npm run typecheck`.
- [x] 4.4 Run `cd frontend; npm run test`.
- [x] 4.5 Run `cd frontend; npm run build`.
- [x] 4.6 Run `npm run openspec:validate`.
