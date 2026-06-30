# Maintenance Calendar and Multi-Device Scheduling Upgrade Plan

## Problem

The current maintenance UI is technically functional but not product-grade. It is embedded inside a single device details page, uses a simple form plus list, and does not give operators a planning view across the fleet.

The next version must support:

- a calendar-style maintenance planning experience;
- visible maintenance blocks across multiple devices;
- creating one maintenance operation for several devices at once;
- editing/cancelling operations without losing per-device auditability;
- clear visual communication that alerts, analytics, and ML are suppressed/excluded during maintenance;
- a scalable backend model that does not become a frontend-only workaround.

## Product Direction

Maintenance should become a first-class operational workspace, not only a tab on a device.

Add a global `Maintenance` section to the app with:

- calendar timeline across devices;
- device filters and grouping by location/type/status;
- month/week/day/agenda modes;
- create/edit drawer;
- multi-device selector;
- status legend;
- conflict indicators;
- quick actions for cancel, finish now, duplicate, and open device.

The device details tab should remain, but it becomes a device-focused slice of the same maintenance system.

## UX Requirements

### Main Maintenance Page

Route:

```text
/maintenance
```

Navigation:

```text
Dashboard | Devices | Maintenance | Alerts | AI Agent | Account
```

Primary layout:

- top toolbar:
  - date navigation: previous, today, next;
  - view switch: month, week, day, agenda;
  - primary action: `Schedule maintenance`;
  - refresh button;
- left filter rail:
  - search device;
  - location filter;
  - device type filter;
  - status filter;
  - policy filter: suppress alerts, exclude analytics, exclude ML;
  - checkbox: show cancelled;
- main calendar:
  - rows represent devices in week/day timeline mode;
  - month mode shows compact grouped event chips;
  - agenda mode shows dense sortable rows;
- right drawer:
  - create/edit form;
  - selected devices;
  - policy toggles;
  - impact summary.

### Calendar Views

#### Week View

This should be the default.

Structure:

```text
Device column | Mon | Tue | Wed | Thu | Fri | Sat | Sun
```

Each device gets one horizontal row. Maintenance windows render as blocks spanning time.

Block content:

- title;
- time range;
- device count if this is a multi-device operation;
- status pill: active, scheduled, finished, cancelled;
- policy icons: alerts, analytics, ML.

#### Day View

Day view should show more precision.

Structure:

```text
Device column | 00:00 ... 24:00
```

Use this for operations teams that schedule short windows.

#### Month View

Month view should be summary-oriented.

Each day cell shows:

- count of scheduled operations;
- count of affected devices;
- active/current marker when applicable;
- top 2-3 event chips, then `+N more`.

Clicking a day opens agenda for that day.

#### Agenda View

Agenda is the operational fallback for dense data.

Columns:

- status;
- title;
- devices;
- location;
- start;
- end;
- policies;
- updated by;
- actions.

### Device Details Maintenance Tab

Keep this tab, but redesign it:

- show a compact mini calendar for the current device;
- show upcoming/active windows in a polished timeline;
- reuse the global drawer component for create/edit;
- add a link: `Open in maintenance calendar`;
- keep chart overlays for maintenance periods.

## Backend Architecture

### Current Limitation

The current source-of-truth API is scoped to one device:

```text
GET /devices/{deviceId}/maintenance-windows
POST /devices/{deviceId}/maintenance-windows
PATCH /devices/{deviceId}/maintenance-windows/{windowId}
POST /devices/{deviceId}/maintenance-windows/{windowId}/cancel
POST /devices/{deviceId}/maintenance-windows/{windowId}/finish-now
```

This works for suppression semantics because downstream systems reason per device, but it is not enough for a calendar across the fleet or one operation affecting several devices.

### Target Model

Introduce a maintenance operation aggregate and keep per-device windows as executable child records.

New concepts:

- `maintenance_operations`: user-facing operation/group.
- `maintenance_windows`: per-device effective windows, owned by an operation.

Why:

- downstream services still consume per-device windows;
- UI can show one operation spanning multiple devices;
- editing a multi-device operation can update all child windows coherently;
- audit and event ordering stay reliable;
- future recurrence and approvals can attach to the operation.

### Database Changes

Add:

```sql
create table maintenance_operations (
    id uuid primary key,
    account_id uuid not null,
    title varchar(255) not null,
    description varchar(5000),
    start_at timestamp not null,
    end_at timestamp not null,
    suppress_alerts boolean not null,
    exclude_from_analytics boolean not null,
    exclude_from_ml boolean not null,
    status varchar(32) not null,
    created_by uuid,
    updated_by uuid,
    created_at timestamp not null,
    updated_at timestamp not null,
    version bigint not null
);
```

Alter:

```sql
alter table maintenance_windows
    add column operation_id uuid;

create index idx_maintenance_operations_account_time
    on maintenance_operations (account_id, start_at, end_at);

create index idx_maintenance_windows_operation
    on maintenance_windows (operation_id);
```

Migration strategy:

1. Backfill one operation per existing window.
2. Set `maintenance_windows.operation_id`.
3. Keep existing per-device API backwards-compatible.

### API Changes

Add account-level public API in `device-service`.

```text
GET /maintenance-operations?from=&to=&deviceIds=&locationId=&status=&includeCancelled=&limit=&offset=
POST /maintenance-operations
GET /maintenance-operations/{operationId}
PATCH /maintenance-operations/{operationId}
POST /maintenance-operations/{operationId}/cancel
POST /maintenance-operations/{operationId}/finish-now
```

Create request:

```json
{
  "title": "Firmware upgrade",
  "description": "Batch maintenance for kitchen devices",
  "deviceIds": ["uuid-1", "uuid-2"],
  "startAt": "2026-07-01T08:00:00Z",
  "endAt": "2026-07-01T10:00:00Z",
  "suppressAlerts": true,
  "excludeFromAnalytics": true,
  "excludeFromMl": true
}
```

Response:

```json
{
  "id": "operation-uuid",
  "accountId": "account-uuid",
  "title": "Firmware upgrade",
  "description": "Batch maintenance for kitchen devices",
  "startAt": "2026-07-01T08:00:00Z",
  "endAt": "2026-07-01T10:00:00Z",
  "status": "SCHEDULED",
  "suppressAlerts": true,
  "excludeFromAnalytics": true,
  "excludeFromMl": true,
  "devices": [
    {
      "deviceId": "uuid-1",
      "deviceName": "Kitchen fridge",
      "locationName": "Kitchen",
      "windowId": "window-uuid-1",
      "status": "SCHEDULED"
    }
  ],
  "createdAt": "2026-06-30T10:00:00Z",
  "updatedAt": "2026-06-30T10:00:00Z",
  "version": 1
}
```

### Eventing

Keep the existing `maintenance-window.changed.v1` event for each child window.

Add optional operation event later:

```text
maintenance-operation.changed.v1
```

Do not make analytics/alert services depend on operation events. They need per-device effective intervals, so `maintenance-window.changed.v1` remains the downstream contract.

### Transaction Rules

Creating a multi-device operation:

1. Validate all devices belong to the current account.
2. Reject decommissioned devices.
3. Create one `maintenance_operations` row.
4. Create one `maintenance_windows` row per device.
5. Publish one `maintenance-window.changed.v1` event per window through the outbox.
6. Record one operation audit entry plus per-device window audit entries.

Updating:

1. Operation-level fields update all non-cancelled, non-finished child windows.
2. If only selected devices change, create missing child windows and cancel removed child windows.
3. Increment operation version.
4. Increment affected window versions.
5. Publish per-window events.

Cancelling:

1. Mark operation cancelled.
2. Cancel all non-finished child windows.
3. Publish per-window cancellation events.

Finishing now:

1. Set operation `endAt=now` where applicable.
2. Set all active child windows `endAt=now`.
3. Publish per-window finished events.

## Frontend Architecture

### New Files

```text
frontend/src/maintenance/maintenanceApi.ts
frontend/src/maintenance/maintenanceTypes.ts
frontend/src/maintenance/calendar/MaintenanceCalendarPage.tsx
frontend/src/maintenance/calendar/MaintenanceCalendarToolbar.tsx
frontend/src/maintenance/calendar/MaintenanceCalendarGrid.tsx
frontend/src/maintenance/calendar/MaintenanceTimelineGrid.tsx
frontend/src/maintenance/calendar/MaintenanceAgenda.tsx
frontend/src/maintenance/calendar/MaintenanceOperationBlock.tsx
frontend/src/maintenance/calendar/MaintenanceFilters.tsx
frontend/src/maintenance/MaintenanceOperationDrawer.tsx
frontend/src/maintenance/MaintenanceDeviceSelector.tsx
frontend/src/maintenance/maintenanceCalendar.utils.ts
```

### Dependencies

Avoid adding a large calendar dependency at first. The required UI is timeline-oriented and domain-specific.

Use custom React/CSS for:

- week grid;
- day timeline;
- month summary;
- agenda table.

Reason:

- calendar libraries usually fight custom resource rows;
- we need stable device rows and maintenance policy indicators;
- styling must fit the product, not a generic calendar widget.

If drag-resize becomes required later, evaluate:

- `react-aria` patterns for accessibility;
- `@dnd-kit/core` for controlled drag interactions.

### State Model

Page state:

```ts
type MaintenanceCalendarView = "week" | "day" | "month" | "agenda";

interface MaintenanceCalendarState {
  view: MaintenanceCalendarView;
  anchorDate: Date;
  filters: {
    search: string;
    locationId: string | "all";
    type: string | "all";
    status: MaintenanceWindowStatus | "all";
    includeCancelled: boolean;
  };
  selectedOperationId: string | null;
  drawerMode: "create" | "edit" | null;
}
```

### Visual Design

Use a calmer operations-console style:

- white/neutral page background;
- dense but readable grid;
- subtle row separators;
- strong status color only on maintenance blocks;
- no large marketing cards;
- no decorative gradients.

Status colors:

- active: green/teal;
- scheduled: blue;
- finished: neutral gray;
- cancelled: red muted.

Policy icons:

- suppress alerts: bell-off icon;
- exclude analytics: chart-off icon;
- exclude ML: cpu/off icon.

Use lucide icons if the project adds that dependency; otherwise use concise text pills initially.

## UX Flow: Create Multi-Device Maintenance

1. User opens `/maintenance`.
2. Clicks `Schedule maintenance`.
3. Drawer opens.
4. User enters title, description, start, end.
5. User selects devices:
   - search;
   - filter by location/type/status;
   - select individual devices;
   - select all visible;
   - show selected count.
6. User sets policies:
   - suppress alerts;
   - exclude from analytics;
   - exclude from ML.
7. Drawer shows impact summary:
   - number of devices;
   - overlapping existing windows;
   - alerts/analytics/ML impact.
8. User saves.
9. Calendar immediately shows one grouped block across selected devices.
10. Device details pages show the relevant child windows.

## Conflict Handling

Detect overlapping maintenance windows for selected devices.

Initial behavior:

- allow overlapping windows;
- show warning in drawer;
- show conflict badge on calendar blocks.

Do not hard-block overlaps yet because real maintenance work can overlap.

Later behavior:

- optional account setting: prevent overlapping suppressing maintenance windows.

## Implementation Steps

### Phase 1: Backend Multi-Device Foundation

- [x] ~~Add `MaintenanceOperation` domain entity.~~
- [x] ~~Add Flyway migration for `maintenance_operations` and `operation_id`.~~
- [x] ~~Backfill existing windows into one operation per window.~~
- [x] ~~Add `MaintenanceOperationRepository`.~~
- [x] ~~Add request/response DTOs.~~
- [x] ~~Add `MaintenanceOperationService`.~~
- [x] ~~Add `MaintenanceOperationController`.~~
- [x] ~~Keep existing device-scoped endpoints working.~~
- [x] ~~Add operation audit events.~~
- [x] ~~Add tests for create/update/cancel/finish multi-device operations.~~
- [ ] Add tests proving one event is published per child window.

### Phase 2: Account-Level Maintenance API

- [x] ~~Add list endpoint with `from/to/deviceIds/locationId/status/includeCancelled`.~~
- [x] ~~Include lightweight device metadata in response.~~
- [x] ~~Add pagination limit/offset.~~
- [x] ~~Add sorting by `startAt`.~~
- [x] ~~Add docs in `docs/api/device-api.md`.~~

### Phase 3: Frontend Calendar Shell

- [x] ~~Add `/maintenance` route.~~
- [x] ~~Add navigation item.~~
- [x] ~~Add `maintenanceApi.ts`.~~
- [x] ~~Build toolbar and filters.~~
- [x] ~~Build week calendar view.~~
- [x] ~~Support custom date ranges beyond a fixed week.~~
- [x] ~~Scale timeline columns by selected range: daily, weekly, monthly.~~
- [x] ~~Build agenda view.~~
- [x] ~~Load operations from account-level API.~~
- [x] ~~Add loading/error/empty states.~~

### Phase 4: Create/Edit Drawer

- [x] ~~Build `MaintenanceOperationDrawer`.~~
- [x] ~~Build multi-device selector.~~
- [x] ~~Support create operation.~~
- [x] ~~Support create operation by clicking an empty calendar cell.~~
- [x] ~~Support create operation by dragging a calendar range left or right.~~
- [x] ~~Support edit operation.~~
- [x] ~~Support cancel operation.~~
- [x] ~~Support finish now.~~
- [x] ~~Show conflict warnings.~~
- [x] ~~Show policy impact summary.~~

### Phase 5: Calendar Polish

- [ ] Add month view.
- [ ] Add day timeline view.
- [ ] Add better status colors and policy icons.
- [ ] Add compact hover details.
- [x] ~~Add keyboard-accessible block selection.~~
- [ ] Add responsive mobile agenda fallback.

### Phase 6: Device Details Integration

- [ ] Extract current inline maintenance UI from `DeviceDetailsPage`.
- [ ] Reuse shared drawer.
- [ ] Add mini calendar/timeline for current device.
- [ ] Add `Open in maintenance calendar`.
- [ ] Keep chart maintenance overlays.

### Phase 7: Testing

- [x] ~~Backend unit/integration tests for operations.~~
- [x] ~~API E2E for multi-device create/list/cancel through the gateway.~~
- [ ] Frontend Playwright test:
  - [x] ~~register;~~
  - [x] ~~create two devices;~~
  - [x] ~~open maintenance calendar;~~
  - [x] ~~create one operation for both devices;~~
  - [x] ~~verify both device rows show the block;~~
  - [x] ~~cancel from the calendar and verify `CANCELLED` through the API;~~
  - [x] ~~click an interval, open details, edit it, and verify the update through the API;~~
  - [x] ~~click an empty calendar cell, preselect the device/date, create operation, and verify it appears in the row;~~
  - [x] ~~drag a calendar range backwards, preselect the device/date range, create operation, and verify it appears in the row;~~
  - [x] ~~verify long date ranges collapse into fewer timeline columns.~~
  - [ ] verify each device details page shows its window;
  - [ ] publish anomaly for one device and verify suppression.
- [x] ~~Docker verification with all services running and the real frontend calendar flow passing.~~

## Acceptance Criteria

- Operators can see all maintenance windows across devices on one calendar.
- Operators can create one maintenance operation for multiple devices.
- Each affected device still gets a per-device maintenance window.
- Alert suppression continues to work per device.
- Analytics/ML exclusion continues to work per device.
- Existing single-device maintenance endpoints remain compatible.
- Device details page is cleaner and visually consistent with the global calendar.
- Docker E2E passes from real frontend.

## Non-Goals For This Upgrade

- Recurring maintenance schedules.
- Approval workflows.
- Drag-and-drop resizing.
- External calendar integrations.
- Technician assignment and work orders.

These should be designed after the calendar and multi-device operation model are stable.
