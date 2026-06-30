# Device Maintenance Windows Platform Architecture

## Purpose

Voltcanary needs first-class maintenance windows for devices. During a maintenance window, missing telemetry and abnormal readings can be expected operational behavior. These periods must be excluded from user analytics, ML training and inference datasets, and alert creation according to explicit policy.

This feature is not a local UI flag and not a device lifecycle status. It is a platform capability that affects multiple independently scalable services:

- `device-service` owns maintenance window commands and source-of-truth state.
- `analytics-service` excludes maintenance periods from derived analytics and ML calls.
- `alert-service` suppresses alert creation according to maintenance policy.
- `frontend` provides scheduling, audit visibility, and timeline overlays.
- `ml-service` receives maintenance-aware datasets/contracts instead of learning from operational downtime.

## Design Principles

1. Services do not read each other's databases.
2. The source of truth lives in one service, but read paths are local to each consumer service.
3. Cross-service propagation uses versioned events and idempotent consumers.
4. Runtime analytics and alert decisions must not depend on synchronous calls to `device-service`.
5. Maintenance policy must be explicit, auditable, and replayable.
6. Historical telemetry remains stored; exclusion happens at interpretation time.
7. The architecture must support replay and recalculation when policy changes.

## Ownership

| Service | Responsibility |
| --- | --- |
| `device-service` | Owns maintenance window lifecycle, validation, user-facing CRUD, audit log, and emits maintenance events. |
| `analytics-service` | Maintains a local maintenance read model, excludes intervals from analytics windows, emits maintenance-aware analytics events. |
| `alert-service` | Maintains a local maintenance read model and suppresses/annotates alerts without calling device-service synchronously. |
| `telemetry-service` | Stores telemetry unchanged and may expose raw timeline data; does not decide maintenance semantics. |
| `ml-service` | Uses maintenance-aware training/inference contracts; excludes intervals from datasets. |
| `frontend` | Provides scheduling UI, active state, history, timeline overlays, and operator actions. |

## Core Architecture

The authoritative maintenance write model is in `device-service`.

```text
Frontend
   |
   v
API Gateway
   |
   v
device-service
   |
   v
maintenance-window.changed.v1
   |
   +--> analytics-service local read model
   +--> alert-service local read model
   +--> future notification/audit consumers
```

Analytics and alert services must use their local read models for hot-path decisions. Synchronous internal APIs are acceptable only for administrative repair, reconciliation, or explicit backfill tooling, not for normal event processing.

## Maintenance Window Domain Model

Entity: `MaintenanceWindow`

```json
{
  "id": "uuid",
  "accountId": "uuid",
  "deviceId": "uuid",
  "title": "Planned sensor replacement",
  "description": "Technician visit; telemetry can be absent.",
  "startAt": "2026-06-30T09:00:00Z",
  "endAt": "2026-06-30T11:00:00Z",
  "policy": {
    "suppressAlerts": true,
    "excludeFromAnalytics": true,
    "excludeFromMl": true
  },
  "status": "SCHEDULED",
  "cancelledAt": null,
  "cancellationReason": null,
  "createdBy": "uuid",
  "updatedBy": "uuid",
  "createdAt": "2026-06-29T21:45:00Z",
  "updatedAt": "2026-06-29T21:45:00Z",
  "version": 1
}
```

Status values:

```text
SCHEDULED - now is before startAt
ACTIVE    - now is within [startAt, endAt)
FINISHED  - now is at or after endAt
CANCELLED - cancelledAt is not null
```

Status should be computed from timestamps and cancellation state. The persisted row should keep immutable facts plus a monotonic `version`.

Default policy:

```json
{
  "suppressAlerts": true,
  "excludeFromAnalytics": true,
  "excludeFromMl": true
}
```

Overlapping windows are allowed. Effective policy is merged by OR:

```text
effectiveSuppressAlerts = any(overlappingWindow.suppressAlerts)
effectiveExcludeFromAnalytics = any(overlappingWindow.excludeFromAnalytics)
effectiveExcludeFromMl = any(overlappingWindow.excludeFromMl)
```

Overlapping duration must be unioned when reporting excluded time so it is not counted twice.

## Device Service

### Database Migration

Add a migration under:

```text
backend/services/device-service/src/main/resources/db/migration
```

Suggested name:

```text
V012__maintenance_windows.sql
```

Schema:

```sql
create table maintenance_windows (
    id uuid primary key,
    account_id uuid not null references accounts (id) on delete cascade,
    device_id uuid not null references devices (id) on delete cascade,
    title varchar(255) not null,
    description text,
    start_at timestamp not null,
    end_at timestamp not null,
    suppress_alerts boolean not null default true,
    exclude_from_analytics boolean not null default true,
    exclude_from_ml boolean not null default true,
    cancelled_at timestamp,
    cancellation_reason varchar(1000),
    created_by uuid,
    updated_by uuid,
    created_at timestamp not null,
    updated_at timestamp not null,
    version bigint not null default 1,
    constraint chk_maintenance_window_range check (end_at > start_at)
);

create index idx_maintenance_windows_device_time
    on maintenance_windows (device_id, start_at, end_at);

create index idx_maintenance_windows_account_time
    on maintenance_windows (account_id, start_at, end_at);

create index idx_maintenance_windows_active_lookup
    on maintenance_windows (device_id, start_at, end_at)
    where cancelled_at is null;
```

Add an outbox table if device-service does not already have a generic outbox abstraction suitable for this event family:

```sql
create table maintenance_event_outbox (
    id uuid primary key,
    event_key varchar(500) not null unique,
    topic varchar(255) not null,
    message_key varchar(255) not null,
    payload_json text not null,
    status varchar(50) not null,
    attempts integer not null default 0,
    next_attempt_at timestamp not null,
    created_at timestamp not null,
    published_at timestamp
);

create index idx_maintenance_event_outbox_pending
    on maintenance_event_outbox (status, next_attempt_at);
```

If the existing `device_event_outbox` can support typed maintenance events cleanly, reuse it instead of creating a second outbox.

### Backend Files

Add:

```text
domain/MaintenanceWindow.java
repository/MaintenanceWindowRepository.java
dto/MaintenanceWindowRequest.java
dto/MaintenanceWindowResponse.java
dto/CancelMaintenanceWindowRequest.java
service/MaintenanceWindowService.java
controller/MaintenanceWindowController.java
producer/MaintenanceWindowOutboxService.java
producer/MaintenanceWindowOutboxPublisher.java
```

### Public API

Endpoints:

```http
GET /devices/{deviceId}/maintenance-windows?from=&to=&status=&limit=&offset=
POST /devices/{deviceId}/maintenance-windows
GET /devices/{deviceId}/maintenance-windows/{windowId}
PATCH /devices/{deviceId}/maintenance-windows/{windowId}
POST /devices/{deviceId}/maintenance-windows/{windowId}/cancel
POST /devices/{deviceId}/maintenance-windows/{windowId}/finish-now
```

Create/update request:

```json
{
  "title": "Planned sensor replacement",
  "description": "Technician visit; telemetry can be absent.",
  "startAt": "2026-06-30T09:00:00Z",
  "endAt": "2026-06-30T11:00:00Z",
  "suppressAlerts": true,
  "excludeFromAnalytics": true,
  "excludeFromMl": true
}
```

Cancel request:

```json
{
  "reason": "Work postponed."
}
```

Validation rules:

- device must belong to the authenticated account;
- `DECOMMISSIONED` devices cannot receive new windows;
- `title` is required after trim, max 255 chars;
- `description` max 5000 chars;
- `startAt` and `endAt` are required;
- `endAt > startAt`;
- cancelled windows cannot be edited;
- finished windows cannot have policy/time range changed;
- `finish-now` is valid only for active windows.

### Internal APIs

Internal APIs are still useful for reconciliation and backfill, but not for hot-path analytics or alert decisions.

Add:

```http
GET /internal/devices/{deviceId}/maintenance-windows?from=&to=&includeCancelled=false
GET /internal/maintenance-windows/changes?updatedAfter=&limit=
```

The second endpoint supports repair jobs if a consumer was down longer than Kafka retention or needs targeted reconciliation.

### Outbox And Event Publication

Every create/update/cancel/finish command must update the database row and enqueue an outbox event in the same transaction.

Events are published to:

```text
maintenance-window.changed.v1
```

Kafka key:

```text
deviceId
```

This preserves per-device maintenance ordering.

Event type in `backend/common/common-events`:

```text
common-events/src/main/java/com/voltcanary/common/events/device/MaintenanceWindowChangedEvent.java
```

Payload:

```json
{
  "eventId": "uuid",
  "eventType": "MAINTENANCE_WINDOW_CREATED",
  "maintenanceWindowId": "uuid",
  "accountId": "uuid",
  "deviceId": "uuid",
  "title": "Planned sensor replacement",
  "description": "Technician visit; telemetry can be absent.",
  "startAt": "2026-06-30T09:00:00Z",
  "endAt": "2026-06-30T11:00:00Z",
  "suppressAlerts": true,
  "excludeFromAnalytics": true,
  "excludeFromMl": true,
  "cancelledAt": null,
  "cancellationReason": null,
  "version": 1,
  "schemaVersion": "v1",
  "correlationId": "uuid-or-null",
  "producer": "device-service",
  "actorUserId": "uuid-or-null",
  "occurredAt": "2026-06-29T21:45:00Z"
}
```

Event types:

```text
MAINTENANCE_WINDOW_CREATED
MAINTENANCE_WINDOW_UPDATED
MAINTENANCE_WINDOW_CANCELLED
MAINTENANCE_WINDOW_FINISHED
```

Consumers must use `(maintenanceWindowId, version)` for idempotency and ordering. If an older version arrives after a newer one, ignore it.

## Analytics Service

### Local Read Model

Add analytics-owned table:

```sql
create table maintenance_windows_read_model (
    maintenance_window_id uuid primary key,
    account_id uuid not null,
    device_id uuid not null,
    title varchar(255) not null,
    start_at timestamp not null,
    end_at timestamp not null,
    suppress_alerts boolean not null,
    exclude_from_analytics boolean not null,
    exclude_from_ml boolean not null,
    cancelled_at timestamp,
    version bigint not null,
    updated_at timestamp not null
);

create index idx_analytics_maintenance_device_time
    on maintenance_windows_read_model (device_id, start_at, end_at)
    where cancelled_at is null;
```

Add processed event tracking if the existing `processed_events` table can be reused, use that.

### Consumer

Add:

```text
consumer/MaintenanceWindowChangedConsumer.java
service/MaintenanceWindowProjectionService.java
repository/MaintenanceWindowReadModelRepository.java
domain/MaintenanceWindowReadModel.java
```

Consumer behavior:

1. Deserialize `MaintenanceWindowChangedEvent`.
2. Mark event idempotently.
3. Upsert read-model row when incoming `version` is newer.
4. Cancelled windows remain in the read model with `cancelledAt`; query helpers exclude them by default.

### Recalculation Behavior

Modify:

```text
backend/services/analytics-service/src/main/java/com/voltcanary/analytics/service/TelemetryValidatedEventHandler.java
backend/services/analytics-service/src/main/java/com/voltcanary/analytics/service/AnalyticsRecalculationService.java
```

`TelemetryValidatedEventHandler`:

- after idempotency mark, check the local read model at `event.timestamp()`;
- if effective `excludeFromAnalytics=true`, do not enqueue recalculation;
- record metric `analytics.maintenance.events_skipped`.

`AnalyticsRecalculationService`:

1. Load maintenance intervals overlapping `[windowStart, windowEnd)`.
2. Build two interval sets:
   - analytics exclusions: `excludeFromAnalytics=true`;
   - ML exclusions: `excludeFromMl=true`.
3. Filter telemetry chart points before baseline, feature, anomaly, and risk calculations.
4. If the effective analytics sample set is empty, mark the job `SKIPPED` with reason `MAINTENANCE_FULL_COVERAGE`.
5. Include maintenance window ids and versions in `inputHash`.
6. Do not call `MlClient` when the ML input is fully covered by `excludeFromMl=true`.
7. Store prediction status `SUPPRESSED_MAINTENANCE` when ML is intentionally skipped.

Add metadata to feature/prediction JSON:

```json
{
  "maintenanceExcludedSampleCount": 42,
  "maintenanceExcludedWindowIds": ["uuid"],
  "maintenanceExcludedDurationSeconds": 3600
}
```

### Recalculation After Maintenance Changes

Changing a maintenance window can invalidate previous analytics results. Add a service-level reaction to `MaintenanceWindowChangedEvent`:

- identify impacted device and time range;
- enqueue analytics recalculation for the affected time buckets;
- supersede active anomalies/recommendations that were generated from now-excluded samples;
- publish new analytics events only after recalculation produces valid non-maintenance results.

This prevents a user from creating a maintenance window after the fact and still seeing stale risk/anomaly results as active truth.

### Analytics Job Status

Extend analytics job statuses:

```text
PENDING
RUNNING
COMPLETED
FAILED
SKIPPED
```

Add/ensure fields:

```text
window_start
window_end
skip_reason
```

`SKIPPED` is a successful terminal state, not an error.

## Alert Service

### Local Read Model

Add alert-owned table:

```sql
create table maintenance_windows_read_model (
    maintenance_window_id uuid primary key,
    account_id uuid not null,
    device_id uuid not null,
    title varchar(255) not null,
    start_at timestamp not null,
    end_at timestamp not null,
    suppress_alerts boolean not null,
    exclude_from_analytics boolean not null,
    exclude_from_ml boolean not null,
    cancelled_at timestamp,
    version bigint not null,
    updated_at timestamp not null
);

create index idx_alert_maintenance_device_time
    on maintenance_windows_read_model (device_id, start_at, end_at)
    where cancelled_at is null;
```

Add:

```text
consumer/MaintenanceWindowChangedConsumer.java
service/MaintenanceWindowProjectionService.java
repository/MaintenanceWindowReadModelRepository.java
domain/MaintenanceWindowReadModel.java
```

### Alert Creation Suppression

Modify:

```text
backend/services/alert-service/src/main/java/com/voltcanary/alert/service/AlertService.java
```

At the beginning of:

```java
handleAnomaly(AnomalyDetectedEvent event)
handleRiskScoreChanged(RiskScoreChangedEvent event)
```

check local read model overlap with event calculation window:

```text
event.windowStart <= maintenance.endAt
event.windowEnd > maintenance.startAt
```

If any overlapping active maintenance window has `suppressAlerts=true`:

- do not create an `Alert`;
- write a suppression audit row;
- increment `alert.alerts.suppressed{reason="maintenance"}`;
- keep the consumed analytics event idempotently marked.

### Suppression Audit

Add an alert-owned table:

```sql
create table alert_suppressions (
    id uuid primary key,
    source_event_id uuid not null,
    device_id uuid not null,
    account_id uuid,
    reason varchar(100) not null,
    maintenance_window_ids text not null,
    alert_type varchar(100),
    severity varchar(50),
    calculation_window_start timestamp,
    calculation_window_end timestamp,
    suppressed_at timestamp not null
);

create unique index ux_alert_suppressions_source_event
    on alert_suppressions (source_event_id);

create index idx_alert_suppressions_device_time
    on alert_suppressions (device_id, suppressed_at desc);
```

This keeps the user-facing alert list clean while preserving operational evidence.

### Existing Active Alerts

For platform-grade behavior, maintenance changes must also affect already-open alerts.

When alert-service consumes `MAINTENANCE_WINDOW_CREATED` or an update that starts/expands suppression:

1. Find unresolved alerts for the same device whose calculation window overlaps the maintenance window.
2. If the alert type is suppressible, resolve it automatically.
3. Store resolution reason:

```text
Resolved by maintenance window <maintenanceWindowId>
```

4. Save normal alert lifecycle event `RESOLVED`.
5. Do not auto-resolve non-suppressible critical/safety categories.

Add policy configuration:

```yaml
alerts:
  maintenance:
    auto-resolve-overlapping-alerts: true
    non-suppressible-alert-types:
      - SAFETY_CRITICAL
      - SECURITY_INCIDENT
```

## Telemetry Service

Telemetry service continues to store raw/validated telemetry unchanged.

Do not drop or mutate telemetry because of maintenance windows. The same raw data may be useful for:

- forensic review;
- post-maintenance validation;
- future model evaluation;
- recalculation with different policies.

Optional platform enhancement:

```http
GET /telemetry/devices/{deviceId}/readings?includeMaintenanceContext=true
```

This can return timeline annotations later, but the authoritative maintenance UI should come from `device-service` public APIs.

## ML Service

The serious platform contract should not rely only on analytics-service skipping calls. ML workflows need explicit maintenance-aware dataset metadata.

### Inference

Analytics-service calls `ml-service` only with filtered inputs. Add request metadata:

```json
{
  "deviceId": "uuid",
  "timestamp": "2026-06-30T12:00:00Z",
  "metrics": {},
  "excludedIntervals": [
    {
      "maintenanceWindowId": "uuid",
      "startAt": "2026-06-30T09:00:00Z",
      "endAt": "2026-06-30T11:00:00Z",
      "reason": "MAINTENANCE"
    }
  ]
}
```

`ml-service` should persist this metadata in model run/prediction records.

### Training

When training pipelines are implemented, dataset preparation must exclude maintenance intervals before feature generation.

Training run metadata must include:

```json
{
  "maintenanceExcludedIntervals": 12,
  "maintenanceExcludedSamples": 3401,
  "maintenancePolicyVersion": "v1",
  "datasetHashIncludesMaintenancePolicy": true
}
```

Dataset hashes must include:

- telemetry sample ids/timestamps;
- feature generation version;
- maintenance window ids;
- maintenance window versions;
- policy fields used for exclusion.

## Frontend

### API Client

Modify:

```text
frontend/src/devices/devicesApi.ts
```

Add:

```ts
export type MaintenanceWindowStatus = "SCHEDULED" | "ACTIVE" | "FINISHED" | "CANCELLED";

export interface MaintenanceWindow {
  id: string;
  deviceId: string;
  accountId: string;
  title: string;
  description: string | null;
  startAt: string;
  endAt: string;
  status: MaintenanceWindowStatus;
  suppressAlerts: boolean;
  excludeFromAnalytics: boolean;
  excludeFromMl: boolean;
  cancelledAt: string | null;
  cancellationReason: string | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface MaintenanceWindowRequest {
  title: string;
  description?: string | null;
  startAt: string;
  endAt: string;
  suppressAlerts: boolean;
  excludeFromAnalytics: boolean;
  excludeFromMl: boolean;
}
```

Add functions:

```ts
listMaintenanceWindows(deviceId, params)
createMaintenanceWindow(deviceId, request)
updateMaintenanceWindow(deviceId, windowId, request)
cancelMaintenanceWindow(deviceId, windowId, reason)
finishMaintenanceWindowNow(deviceId, windowId)
```

### Device Details Navigation

Modify:

```text
frontend/src/devices/details/DeviceTabs.tsx
```

Add tab:

```text
maintenance
```

Label:

```text
Maintenance
```

### Components

Create:

```text
frontend/src/devices/details/maintenance/MaintenancePanel.tsx
frontend/src/devices/details/maintenance/MaintenanceWindowForm.tsx
frontend/src/devices/details/maintenance/MaintenanceWindowList.tsx
frontend/src/devices/details/maintenance/maintenance.utils.ts
```

The panel should provide:

- active maintenance summary;
- upcoming windows;
- completed/cancelled history;
- create scheduled window;
- start now;
- edit;
- finish now;
- cancel with reason;
- policy toggles for alerts, analytics, and ML.

### DeviceDetailsPage Integration

Modify:

```text
frontend/src/pages/DeviceDetailsPage.tsx
```

Add state:

```ts
const [maintenanceWindows, setMaintenanceWindows] = useState<MaintenanceWindow[]>([]);
```

Load windows for the visible timeline and near future:

```text
from = chart range from, or now - 7d
to   = chart range to, or now + 30d
```

Add active badge in the device header:

```text
Maintenance active until <time>
```

### Telemetry Chart Overlays

Extend the internal `MetricChart` component in `DeviceDetailsPage.tsx`:

```ts
maintenanceWindows?: MaintenanceWindow[];
```

For each overlapping window, render a background SVG rect before the polyline:

```tsx
<rect
  className="chart-maintenance-window"
  x={x1}
  y={padding.top}
  width={Math.max(1, x2 - x1)}
  height={height - padding.top - padding.bottom}
>
  <title>{`Maintenance: ${window.title}`}</title>
</rect>
```

Add CSS in:

```text
frontend/src/app/styles.css
```

```css
.chart-maintenance-window {
  fill: rgba(107, 114, 128, 0.16);
  stroke: rgba(107, 114, 128, 0.32);
  stroke-dasharray: 4 4;
}
```

### Analytics And Alerts UI

Analytics tab:

- show a compact notice when the selected period intersects maintenance;
- display excluded duration/sample count when backend metadata is available.

Alerts tab:

- show that alert creation is suppressed while active maintenance is in effect;
- continue showing historical alerts and auto-resolved alerts with their lifecycle reason.

## API Gateway And Deployment

Gateway routes likely already send `/devices/**` to `device-service`. No new public route prefix is required.

Kafka topics to add:

```text
maintenance-window.changed.v1
```

Update:

```text
scripts/create-topics.sh
deploy/docker-compose/init/redpanda/create-topics.sh
deploy/k8s/base/config.yaml
deploy/helm/voltcanary/values*.yaml
```

Consumer groups:

```text
analytics-service-maintenance
alert-service-maintenance
```

Scaling model:

- `device-service` scales for command/API load.
- outbox publisher scales independently with row leasing.
- analytics maintenance consumer scales by Kafka partitions.
- alert maintenance consumer scales by Kafka partitions.
- analytics recalculation workers scale separately from projection consumers.
- alert notification workers remain independent from suppression projection.

## Observability

Device-service metrics:

```text
device.maintenance.windows.created
device.maintenance.windows.updated
device.maintenance.windows.cancelled
device.maintenance.outbox.pending
device.maintenance.outbox.published
device.maintenance.outbox.failed
```

Analytics-service metrics:

```text
analytics.maintenance.events_projected
analytics.maintenance.events_skipped
analytics.maintenance.samples_excluded
analytics.maintenance.jobs_skipped
analytics.maintenance.projection_lag_seconds
```

Alert-service metrics:

```text
alert.maintenance.events_projected
alert.alerts.suppressed
alert.alerts.auto_resolved_by_maintenance
alert.maintenance.projection_lag_seconds
```

Logs should include:

- `correlationId`;
- `maintenanceWindowId`;
- `deviceId`;
- `accountId`;
- event version;
- consumer decision.

## Consistency Model

Maintenance propagation is eventually consistent through Kafka. This is acceptable because:

- commands are immediately visible in device-service UI;
- analytics and alert consumers process ordered per-device events;
- stale analytics/alerts are corrected by maintenance change consumers through recalculation and auto-resolution.

To reduce visible inconsistency:

1. UI shows maintenance state directly from device-service.
2. Device-service emits events transactionally through outbox.
3. Consumers expose projection lag metrics.
4. Reconciliation endpoints and jobs repair missed events.

## Reconciliation

Each consumer should have a scheduled reconciliation job:

1. Read `updatedAfter` from local checkpoint.
2. Call `device-service` internal `/internal/maintenance-windows/changes`.
3. Upsert newer versions into local read model.
4. Record projection lag and repaired count.

This is not the hot path; it is a safety net for operational correctness.

## Testing Strategy

### Device Service

Add tests for:

- create valid window;
- reject invalid time range;
- reject device outside user account;
- reject decommissioned device;
- list by time range;
- update increments version;
- cancel increments version;
- finish-now updates end time and emits event;
- outbox row is written in the same transaction.

### Analytics Service

Add tests for:

- maintenance event projection is idempotent;
- older event version is ignored;
- telemetry event inside `excludeFromAnalytics` window does not enqueue recalculation;
- partial overlap filters samples;
- full coverage marks job `SKIPPED`;
- input hash changes when maintenance policy/version changes;
- ML request includes excluded interval metadata;
- ML is suppressed when input is fully excluded.

### Alert Service

Add tests for:

- maintenance event projection is idempotent;
- anomaly event overlapping `suppressAlerts=true` creates suppression audit, not alert;
- risk event overlapping `suppressAlerts=true` creates suppression audit, not alert;
- `suppressAlerts=false` preserves normal alert behavior;
- creating maintenance after alert creation auto-resolves suppressible overlapping alerts;
- non-suppressible alert types are not auto-resolved.

### Frontend

Add tests for:

- maintenance tab renders active/upcoming/history;
- create/edit/cancel/finish actions call expected API;
- active badge appears;
- chart overlay is rendered for overlapping windows;
- no layout shift when windows are loaded.

## End-To-End Verification

Scenario 1: Scheduled maintenance suppresses downstream behavior.

1. Create device.
2. Create maintenance window from `now - 5m` to `now + 30m`.
3. Send telemetry inside the window.
4. Verify telemetry is stored.
5. Verify analytics job is not created or is skipped.
6. Verify ML is not called for excluded input.
7. Verify alert is not created.
8. Verify suppression audit exists.
9. Verify UI shows active maintenance and chart overlay.

Scenario 2: Backdated maintenance repairs stale analytics/alerts.

1. Send anomalous telemetry.
2. Let analytics create anomaly/risk and alert-service create alert.
3. Create maintenance window covering that period.
4. Verify analytics recalculation supersedes affected active analytics results.
5. Verify alert-service auto-resolves suppressible overlapping alert.
6. Verify UI shows the alert lifecycle reason.

Scenario 3: Maintenance ends.

1. Finish active window.
2. Send telemetry outside the window.
3. Verify analytics resumes.
4. Verify alerts resume according to normal rules.

## Implementation Plan

### Phase 1. Shared Event Contract

1. ~~Add `MaintenanceWindowChangedEvent` to `backend/common/common-events`.~~
2. ~~Add event docs under `docs/events`.~~
3. ~~Add Kafka topic configuration for `maintenance-window.changed.v1`.~~

### Phase 2. Device-Service Write Model

1. ~~Add migration.~~
2. ~~Add entity/repository/service/controller.~~
3. ~~Add public API.~~
4. ~~Add outbox publisher.~~
5. ~~Add internal reconciliation APIs.~~
6. ~~Add tests.~~

### Phase 3. Analytics Projection And Exclusion

1. ~~Add read-model migration.~~
2. ~~Add consumer/projection service.~~
3. ~~Add reconciliation job.~~
4. ~~Modify telemetry event handling.~~
5. ~~Modify recalculation filtering and job statuses.~~
6. ~~Modify ML request metadata.~~
7. ~~Add tests.~~

### Phase 4. Alert Projection And Suppression

1. ~~Add read-model migration.~~
2. ~~Add suppression audit migration.~~
3. ~~Add consumer/projection service.~~
4. ~~Add reconciliation job.~~
5. ~~Modify alert creation.~~
6. ~~Add auto-resolution behavior.~~
7. ~~Add tests.~~

### Phase 5. Frontend

1. ~~Add maintenance API functions and types.~~
2. ~~Add maintenance tab.~~
3. ~~Add active badge.~~
4. ~~Add chart overlays.~~
5. ~~Add analytics/alerts notices.~~
6. Add frontend tests.

### Phase 6. Documentation And Operational Readiness

1. ~~Update `docs/api/device-api.md`.~~
2. ~~Update `docs/api/analytics-api.md`.~~
3. ~~Update `docs/api/alerts-api.md`.~~
4. ~~Update `docs/architecture/02-service-boundaries.md`.~~
5. ~~Update `docs/architecture/03-data-flow.md`.~~
6. ~~Update deployment topic/config docs.~~
7. ~~Add manual e2e verification checklist.~~

## Architecture Decisions To Record

Create ADR:

```text
docs/adr/ADR-010-maintenance-window-platform.md
```

Decision:

- `device-service` owns maintenance windows.
- Maintenance changes propagate through events.
- Analytics and alert services use local read models.
- Synchronous device-service calls are not used in hot paths.
- Telemetry remains immutable and maintenance is applied at interpretation time.

Consequences:

- More moving parts than synchronous lookup.
- Better scalability and service independence.
- Consumers need reconciliation and projection lag monitoring.
- Recalculation/auto-resolution logic is required for backdated changes.
