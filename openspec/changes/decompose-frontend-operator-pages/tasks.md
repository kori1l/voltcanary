# Tasks: Decompose Frontend Operator Pages

## 1. Device Details

- [ ] 1.1 Extract device/telemetry/analytics loading into typed hooks.
- [ ] 1.2 Extract telemetry chart rendering and chart preference storage into feature modules.
- [ ] 1.3 Extract maintenance window state and commands.
- [ ] 1.4 Extract data-source, sensor, and analytics-config registry workflows.
- [ ] 1.5 Extract simulator YAML parsing, editing, validation, and runtime commands.
- [ ] 1.6 Keep `/devices/:deviceId` route behavior compatible.

## 2. ML Settings

- [ ] 2.1 Extract ML workspace data loading and refresh orchestration.
- [ ] 2.2 Extract lifecycle confirmation/impact action handling.
- [ ] 2.3 Extract labels and async horizon generation state.
- [ ] 2.4 Extract training draft/prerequisite validation.
- [ ] 2.5 Extract model registry, deployment policy, and monitoring tabs into feature modules.
- [ ] 2.6 Keep `/settings/ml` route behavior compatible.

## 3. Secondary Large Pages

- [ ] 3.1 Audit `MaintenanceCalendarPage.tsx`, `AlertsPage.tsx`, and `DevicesPage.tsx` before adding new workflow behavior.
- [ ] 3.2 Extract feature hooks/components first when a new change touches one of those pages.

## 4. Tests And Verification

- [ ] 4.1 Add unit tests for extracted hooks and pure workflow helpers.
- [ ] 4.2 Keep or add page-level tests for critical ML and device investigation workflows.
- [ ] 4.3 Run `cd frontend; npm run typecheck`.
- [ ] 4.4 Run `cd frontend; npm run test`.
- [ ] 4.5 Run `cd frontend; npm run build`.
- [ ] 4.6 Run `npm run openspec:validate`.
