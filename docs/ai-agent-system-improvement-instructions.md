# VoltCanary System Improvement Instructions For AI Coding Agent

Status: implementation instruction for coding agent  
Date: 2026-06-15  
Primary inputs: `docs/frontend-improvement-requirements.md`, `docs/frontend-redesign-audit.md`, current frontend code, current AI agent code, current alert and analytics services.

## 1. Goal

Upgrade VoltCanary so the product flow matches the frontend improvement requirements:

- normal users start from Devices, not Dashboard;
- AI is a contextual assistant, not a normal top-level page;
- users can create normal and demo devices without raw JSON/YAML;
- Alerts become an incident inbox;
- alert rules become a first-class configurable concept;
- alert instances link to telemetry windows and rule details;
- notification channels move out of the alert inbox;
- admin-only AI model settings stay available but do not dominate the operator UI.

This document is for an AI coding agent implementing the work. It describes which services need backend changes, which endpoints are missing, and how to rework the frontend.

## 2. Current State To Respect

Do not restart the AI model provider work from scratch. Current code already includes a significant part of it:

- `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/controller/AiAgentController.java` exposes model provider CRUD endpoints.
- `frontend/src/aiAgent/aiAgentApi.ts` already contains provider/model API functions.
- `frontend/src/pages/AiAgentSettingsPage.tsx` already provides an admin-ish AI model settings page.
- `frontend/src/pages/AiAgentPage.tsx` already supports provider/model selection on plan creation.

The bigger product gaps are now:

- no persisted alert-rule management API;
- analytics rules are still service configuration, not account/device-scoped user rules;
- alerts expose `ruleId` but cannot open/edit a rule;
- frontend still exposes Dashboard and AI Agent as normal sidebar items;
- device creation is still a backend-shaped modal;
- simulator setup is still too technical;
- AI assistant is still a page with Context JSON, not a global contextual drawer.

## 3. Backend Work Overview

Required service changes:

- `alert-service`: add persisted alert rules, rule CRUD, rule testing, notification routing by rule, alert grouping support.
- `analytics-service`: read effective rules from persisted rule configuration or a rule snapshot API instead of only static `app.analytics.rules`.
- `ai-agent-service`: add assistant tools for rule management, alert explanation, telemetry navigation, and demo-device orchestration.
- `device-service`: optionally add an orchestration endpoint for guided device creation if the frontend should avoid multi-step client orchestration.
- `simulator-service`: add preset/demo profiles and a simpler "create demo config" flow if current template endpoints are not enough.
- `api-gateway`: route all new endpoints and enforce role-aware access.

Frontend work is the main deliverable and is described in detail in section 10.

## 4. Alert Rules Backend

### 4.1 Problem

Current alert behavior:

- `alert-service` creates alert instances from analytics events.
- `AlertResponse` includes `ruleId`.
- `AlertsPage` displays `Rule` in technical trace details.
- There is no UI/API for listing, creating, editing, enabling, disabling, or testing rules.

Current analytics behavior:

- `analytics-service` has static rule configuration in `AnalyticsRuleProperties`.
- Built-in rules are `POWER_SPIKE`, `CONSUMPTION_GROWTH`, and `LONG_CYCLE`.
- `AnomalyDetector` emits anomalies with `ruleId` and `ruleVersion`.

This is not enough for the frontend requirements. Users must be able to manage rules.

### 4.2 Add Alert Rule Persistence

Add tables in `alert-service` unless a cross-service policy service is introduced. Keep it in `alert-service` for this iteration because notification routing and alert instances live there.

Create migration:

```text
backend/services/alert-service/src/main/resources/db/migration/V015__alert_rules.sql
```

Suggested tables:

```sql
create table alert_rules (
  id uuid primary key,
  account_id uuid not null,
  name varchar(160) not null,
  description varchar(1000),
  rule_type varchar(64) not null,
  enabled boolean not null default true,
  scope_type varchar(32) not null,
  scope_id uuid,
  device_type varchar(100),
  metric varchar(120) not null,
  condition_type varchar(64) not null,
  threshold numeric(18,6),
  threshold_unit varchar(32),
  baseline_multiplier numeric(10,4),
  min_points integer,
  severity varchar(32) not null,
  cooldown_seconds integer not null default 900,
  deduplication_window_seconds integer not null default 900,
  notification_channel_ids jsonb not null default '[]',
  config jsonb not null default '{}',
  version integer not null default 1,
  created_by uuid,
  updated_by uuid,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create index idx_alert_rules_account on alert_rules(account_id);
create index idx_alert_rules_scope on alert_rules(account_id, scope_type, scope_id);
create index idx_alert_rules_type_metric on alert_rules(account_id, rule_type, metric);
create unique index uq_alert_rules_account_name on alert_rules(account_id, lower(name));
```

Suggested enum values:

- `rule_type`: `POWER_SPIKE`, `CONSUMPTION_GROWTH`, `LONG_CYCLE`, `OFFLINE`, `THRESHOLD`, `CUSTOM`.
- `scope_type`: `FLEET`, `LOCATION`, `DEVICE_TYPE`, `DEVICE`.
- `condition_type`: `ABOVE_THRESHOLD`, `BELOW_THRESHOLD`, `BASELINE_MULTIPLIER`, `GROWTH_RATE`, `MISSING_TELEMETRY`, `LONG_HIGH_RUN`.
- `severity`: reuse `AlertSeverity`.

### 4.3 Add Rule DTOs

Add DTOs under `backend/services/alert-service/src/main/java/com/voltcanary/alert/dto`:

- `AlertRuleResponse`
- `CreateAlertRuleRequest`
- `UpdateAlertRuleRequest`
- `AlertRuleTestRequest`
- `AlertRuleTestResponse`
- `AlertRuleScopeResponse`

Minimum response fields:

```text
id
accountId
name
description
ruleType
enabled
scopeType
scopeId
deviceType
metric
conditionType
threshold
thresholdUnit
baselineMultiplier
minPoints
severity
cooldownSeconds
deduplicationWindowSeconds
notificationChannelIds
config
version
createdAt
updatedAt
```

Validation rules:

- name required, max 160;
- metric required except pure `OFFLINE` rules if no metric is needed;
- severity required;
- `DEVICE` scope requires `scopeId`;
- `LOCATION` scope requires `scopeId`;
- `DEVICE_TYPE` scope requires `deviceType`;
- threshold required for threshold conditions;
- cooldown and deduplication windows must be positive.

### 4.4 Add Rule Endpoints

Add `AlertRuleController`:

```text
GET    /alert-rules
POST   /alert-rules
GET    /alert-rules/{ruleId}
PUT    /alert-rules/{ruleId}
DELETE /alert-rules/{ruleId}
POST   /alert-rules/{ruleId}/enable
POST   /alert-rules/{ruleId}/disable
POST   /alert-rules/test
GET    /alert-rules/effective?deviceId={deviceId}
```

Support filters on `GET /alert-rules`:

```text
enabled
ruleType
scopeType
deviceId
locationId
deviceType
metric
```

`GET /alert-rules/effective?deviceId=` should return built-in plus user-configured rules that apply to a device. This is useful for the Device Details and Telemetry UI.

### 4.5 Rule Testing Behavior

`POST /alert-rules/test` should evaluate a draft rule against recent telemetry.

Request:

```json
{
  "deviceId": "uuid",
  "rule": {
    "ruleType": "POWER_SPIKE",
    "metric": "activePowerW",
    "conditionType": "BASELINE_MULTIPLIER",
    "baselineMultiplier": 1.8,
    "severity": "WARNING"
  },
  "from": "2026-06-15T10:00:00Z",
  "to": "2026-06-15T11:00:00Z"
}
```

Response:

```json
{
  "matched": true,
  "matchCount": 4,
  "wouldCreateAlert": true,
  "severity": "WARNING",
  "previewTitle": "Power spike detected",
  "previewDescription": "Active power exceeded the configured rule 4 times.",
  "evidence": [
    {
      "timestamp": "2026-06-15T10:12:00Z",
      "metric": "activePowerW",
      "value": 128.4,
      "threshold": 100.0
    }
  ]
}
```

Implementation options:

- Short-term: `alert-service` calls `telemetry-service`/`analytics-service` through existing internal APIs.
- Better: `analytics-service` exposes a rule evaluation endpoint and `alert-service` only owns persistence and notification routing.

Prefer the better split if time allows.

## 5. Analytics Service Rule Integration

### 5.1 Problem

`AnomalyDetector` currently reads only `AnalyticsRuleProperties`, which is static application configuration. A user-created rule in `alert-service` will not affect anomaly detection unless analytics can read effective rules.

### 5.2 Required Integration

Add one of these patterns:

1. `analytics-service` owns rule evaluation and stores replicated rule definitions.
2. `analytics-service` calls `alert-service` for effective rules by device before detection.
3. `alert-service` owns only alert rules and emits a rule-config-changed event consumed by analytics.

Recommended for this repo: option 3 eventually, option 2 for MVP.

~~MVP endpoint from `alert-service`:~~ Done.

```text
GET /internal/alert-rules/effective?deviceId={deviceId}
```

It should return only rules the analytics service needs:

```text
id
ruleType
enabled
metric
conditionType
threshold
baselineMultiplier
minPoints
severity
score
version
```

Then update `analytics-service`:

- ~~Add `AlertRuleClient`.~~ Done.
- ~~In anomaly calculation, load effective rules for the device.~~ Done.
- ~~Fall back to `AnalyticsRuleProperties` if the rule service is unavailable.~~ Done: client returns no user rules and static detection continues.
- ~~Emit `ruleId` using the persisted rule ID for user-created rules.~~ Done.
- Preserve built-in rule IDs such as `POWER_SPIKE` for static fallback.

### 5.3 Rule Versioning

When a rule changes, increment version. Analytics events and alerts must carry:

- `ruleId`
- `ruleVersion`
- calculation window
- input hash

Frontend should show rule name/version in details, not raw technical IDs in the primary row.

## 6. Alert Service Inbox Improvements

### 6.1 Add Rule Filter

Current `AlertListFilters` supports `alertType` but not `ruleId`. Add:

```text
ruleId
```

Update:

- `AlertListFilters`
- `AlertController`
- `AlertService.specification`
- frontend `AlertFilters`

### 6.2 Add Grouping Endpoint

For repeated alerts, add:

```text
GET /alerts/groups
```

Filters:

```text
status
severity
deviceId
ruleId
from
to
```

Response item:

```text
groupKey
ruleId
ruleName
alertType
deviceId
severity
statusSummary
count
firstCreatedAt
lastCreatedAt
latestAlertId
sampleAlert
```

This supports a dense inbox and device-scoped repeated-alert grouping.

### 6.3 Notification Channels

Keep current notification channel endpoints, but move them out of the primary Alerts page UI.

Frontend target location:

- `Settings -> Notifications`, or
- `Alerts -> Rules -> Notification channels` secondary tab.

No new backend endpoint is required for simple relocation.

## 7. AI Agent Service Improvements

### 7.1 Current AI Agent Capabilities

Current `ActionPlanner` tools:

- `CREATE_DEVICE`
- `CREATE_DATA_SOURCE`
- `CREATE_SIMULATOR_CONFIG`
- `VALIDATE_SIMULATOR_YAML`

Current problems:

- assistant page exposes `Context JSON`;
- normal users see provider/model implementation details;
- generated plans use internal tool names;
- planner contains mojibake Russian token checks, which should be removed or replaced with valid Unicode/source text;
- no tools for alert explanation, telemetry navigation, or rule creation.

### 7.2 Add Agent Tools

Extend `AgentTool` with:

```text
CREATE_ALERT_RULE
UPDATE_ALERT_RULE
TEST_ALERT_RULE
EXPLAIN_ALERT
OPEN_TELEMETRY_WINDOW
CREATE_DEMO_DEVICE
START_SIMULATION
STOP_SIMULATION
```

The agent must still only plan. Mutating tools require confirmation.

### 7.3 Add Orchestrator Actions

Update `AiAgentOrchestrator` execution logic:

- `CREATE_ALERT_RULE`: call `alert-service` `POST /alert-rules`.
- `UPDATE_ALERT_RULE`: call `PUT /alert-rules/{id}`.
- `TEST_ALERT_RULE`: call `POST /alert-rules/test`; read-only.
- `EXPLAIN_ALERT`: call `GET /alerts/{id}`, optionally `GET /alert-rules/{ruleId}`, and telemetry/analytics endpoints; read-only.
- `OPEN_TELEMETRY_WINDOW`: return a frontend navigation payload, not a backend mutation.
- `CREATE_DEMO_DEVICE`: either call a new device-service orchestration endpoint or execute existing create device + create data source + simulator config sequence.
- `START_SIMULATION`: call simulator-service start endpoint.
- `STOP_SIMULATION`: call simulator-service stop endpoint.

Execution result for navigation tools should include:

```json
{
  "route": "/devices/{deviceId}?tab=telemetry&from=...&to=...&metric=activePowerW&alertId=..."
}
```

Frontend should render this as an `Open telemetry` action.

### 7.4 Plan Output UX

Update system prompt and sanitizer so user-visible plan steps are product language:

- "Create demo fridge" instead of `CREATE_DEVICE`.
- "Attach simulator telemetry source" instead of `CREATE_DATA_SOURCE`.
- "Create power spike rule" instead of `CREATE_ALERT_RULE`.

Keep tool names in the payload only, not as primary UI text.

### 7.5 Admin Access

AI model provider settings are admin-only.

Backend must enforce this for:

- provider CRUD;
- model CRUD;
- secret rotation;
- provider testing if it uses stored secrets.

Regular users may use enabled default model providers but should not see keys, provider diagnostics, fallback internals, or settings links.

## 8. Device And Simulator Backend Improvements

### 8.1 Optional Device Setup Orchestration Endpoint

The current frontend creates a simulator device by manually calling:

- `POST /devices`
- `POST /devices/{id}/data-sources`
- `POST /devices/{id}/data-sources/{id}/token`
- `GET /simulation/templates/{profile}`
- `PUT /simulation/devices/{id}/config`

This works but makes the frontend carry backend orchestration. For a cleaner wizard and AI assistant, add an orchestration endpoint.

Recommended endpoint in `device-service` or a thin orchestration endpoint in `ai-agent-service`:

```text
POST /device-setup/demo-device
```

Request:

```json
{
  "name": "Kitchen fridge demo",
  "type": "FRIDGE",
  "locationId": "uuid",
  "sourceProtocol": "HTTP",
  "preset": "POWER_SPIKE",
  "startImmediately": true
}
```

Response:

```json
{
  "deviceId": "uuid",
  "dataSourceId": "uuid",
  "simulationConfigId": "uuid",
  "simulationRunId": "uuid",
  "telemetryRoute": "/devices/{deviceId}?tab=telemetry"
}
```

If this endpoint is not implemented, frontend can still use current endpoints but should isolate orchestration in a single `createDemoDevice()` client function.

### 8.2 Simulator Presets

Current simulator API exposes templates and YAML validation. Add or formalize:

```text
GET /simulation/presets
GET /simulation/templates/{profile}?preset={preset}&sourceId={sourceId}
```

Minimum presets:

- `NORMAL_OPERATION`
- `POWER_SPIKE`
- `OFFLINE_WINDOW`
- `CONSUMPTION_GROWTH`

The response should include user-facing metadata:

```text
name
description
metrics
expectedAlerts
defaultDuration
defaultInterval
yamlConfig
```

## 9. API Gateway Updates

Add routes for:

- `/alert-rules/**` to `alert-service`;
- `/alerts/groups` to `alert-service`;
- `/simulation/presets` if added;
- `/device-setup/demo-device` if added.

Ensure gateway auth forwards:

- `X-User-Id`
- `X-User-Role`
- correlation ID

Do not rely on frontend-only hiding for admin features.

## 10. Frontend Implementation Plan

Frontend is the main visible deliverable. Work in this order.

### 10.1 Routing And Navigation

Files:

- `frontend/src/app/App.tsx`
- `frontend/src/app/AppLayout.tsx`

Changes:

- Redirect `/` to `/devices`.
- Redirect `/dashboard` to `/devices` or keep route hidden and dev-only.
- Remove `Dashboard` from normal sidebar.
- Remove `AI Agent` from normal sidebar.
- Replace `Account` with `Settings`, or route `/account` into `/settings`.
- Keep `/ai-agent/settings` admin-only.
- Keep `/ai-agent` hidden, admin/dev-only, or replace it with the global assistant drawer.

Target normal nav:

```text
Devices
Alerts
Settings
```

Admin nav may add:

```text
Admin
```

### 10.2 Add Global Assistant Drawer

New files:

```text
frontend/src/aiAgent/AssistantDrawer.tsx
frontend/src/aiAgent/AssistantButton.tsx
frontend/src/aiAgent/assistantContext.ts
```

or use feature folder:

```text
frontend/src/features/assistant/
```

Behavior:

- floating button in authenticated layout;
- opens drawer;
- drawer accepts natural-language request;
- drawer automatically receives page context;
- Context JSON is hidden under Advanced;
- plan preview is primary;
- execution requires confirmation for mutating actions;
- navigation results render as clickable actions.

Page context examples:

On Devices:

```ts
{
  page: "devices",
  selectedLocationId,
  filters
}
```

On Device Details:

```ts
{
  page: "device-details",
  deviceId,
  activeTab,
  selectedMetric,
  timeRange
}
```

On Alerts:

```ts
{
  page: "alerts",
  selectedAlertId,
  filters
}
```

### 10.3 Keep AI Settings But Move To Admin

Files:

- `frontend/src/pages/AiAgentSettingsPage.tsx`
- `frontend/src/aiAgent/aiAgentApi.ts`

Required improvements:

- make the page admin-only in router/layout;
- remove normal-user links to model settings;
- add clear empty/error states for missing encryption key;
- keep API key write-only;
- keep provider/model diagnostics in admin UI only.

Do not delete the existing settings work.

### 10.4 Devices Page Wizard

Files:

- `frontend/src/pages/DevicesPage.tsx`
- optionally new components under `frontend/src/devices/`

Replace the current single Add Device modal with a wizard:

```text
Step 1: Device basics
Step 2: Location
Step 3: Telemetry source
Step 4: Review
```

Primary source choices:

- Smart plug/API
- MQTT sensor
- Simulator/demo source

For simulator:

- choose preset;
- show expected telemetry and expected alerts;
- source ID generated automatically or advanced-only;
- YAML hidden under Advanced.

Move to Advanced:

- metadata JSON;
- raw source ID;
- raw protocol;
- credentials references.

Implementation detail:

- If backend orchestration endpoint is not available, create a frontend helper `createDeviceWithSource()` and `createDemoDevice()` to isolate the current multi-call flow from JSX.

### 10.5 Alerts API Client

File:

- `frontend/src/alerts/alertsApi.ts`

Add:

```ts
export interface AlertRule { ... }
export interface CreateAlertRuleRequest { ... }
export interface UpdateAlertRuleRequest { ... }
export interface AlertRuleTestRequest { ... }
export interface AlertRuleTestResponse { ... }
export interface AlertGroup { ... }

listAlertRules(filters)
getAlertRule(ruleId)
createAlertRule(request)
updateAlertRule(ruleId, request)
deleteAlertRule(ruleId)
enableAlertRule(ruleId)
disableAlertRule(ruleId)
testAlertRule(request)
listEffectiveAlertRules(deviceId)
listAlertGroups(filters)
```

Extend `AlertFilters` with:

```ts
ruleId?: string;
from?: string;
to?: string;
```

### 10.6 Alerts Page Redesign

File:

- `frontend/src/pages/AlertsPage.tsx`

Replace three large Open/Acknowledged/Resolved columns with tabs and dense list/table:

```text
Tabs: Inbox | Rules | Notifications
```

Inbox columns:

- severity;
- status;
- title;
- device;
- location;
- rule;
- age;
- actions.

Actions:

- View device;
- Open telemetry;
- View rule;
- Acknowledge;
- Resolve;
- Delivery details.

Technical IDs go behind details:

- source event ID;
- anomaly ID;
- risk score ID;
- calculation input hash;
- calculation window.

Move current notification channel form out of the top of the inbox. Put it under `Notifications` or Settings.

### 10.7 Rules UI

New components:

```text
frontend/src/alerts/AlertRulesTab.tsx
frontend/src/alerts/AlertRuleForm.tsx
frontend/src/alerts/AlertRuleTestPanel.tsx
frontend/src/alerts/AlertRuleBadge.tsx
```

Minimum features:

- list rules;
- filter by enabled/type/scope/metric;
- create rule;
- edit rule;
- enable/disable rule;
- delete rule;
- test rule against recent telemetry;
- link from alert row to rule details.

Rule form fields:

- name;
- description;
- enabled;
- rule type;
- scope;
- device/location/device type selector where needed;
- metric;
- condition;
- threshold or multiplier;
- severity;
- cooldown;
- deduplication window;
- notification channels.

Use structured controls, not raw JSON.

### 10.8 Device Details Restructure

File:

- `frontend/src/pages/DeviceDetailsPage.tsx`

This file is large. Do not keep adding more nested JSX to it. Split into components before major UI changes.

Suggested components:

```text
frontend/src/devices/details/DeviceHeader.tsx
frontend/src/devices/details/DeviceOverviewTab.tsx
frontend/src/devices/details/DeviceTelemetryTab.tsx
frontend/src/devices/details/DeviceAlertsTab.tsx
frontend/src/devices/details/DeviceConfigurationTab.tsx
frontend/src/devices/details/DeviceDemoTools.tsx
frontend/src/devices/details/deviceDetailsModel.ts
```

Target tabs:

- Overview
- Telemetry
- Alerts
- Configuration

Move or hide:

- Analytics separate tab if it duplicates telemetry/overview;
- Registry renamed to Configuration;
- Simulation renamed to Demo Tools and shown only when relevant.

Telemetry tab:

- chart interactions;
- sparse data states;
- event markers;
- link event to alert and rule;
- `Configure alert` opens create rule form with metric/time context.

Alerts tab:

- active alerts first;
- history grouped by rule/type;
- Open telemetry action preserves time window;
- View rule action.

### 10.9 Settings Page

Create:

```text
frontend/src/pages/SettingsPage.tsx
```

Move/compose:

- account/profile from `AccountPage`;
- security/email verification;
- notification channels;
- technical details disclosure;
- admin-only link to AI model settings.

Normal users should not see provider configuration controls unless they are admins.

### 10.10 Frontend State And Error Handling

Required:

- convert raw backend errors to useful messages where possible;
- show empty states with real next actions;
- avoid showing raw IDs in primary content;
- add loading states per tab/action, not only page-level where possible;
- preserve filters in URLs for Alerts and Device Details telemetry windows.

Example telemetry URL:

```text
/devices/{deviceId}?tab=telemetry&metric=activePowerW&from=2026-06-15T10:00:00Z&to=2026-06-15T10:30:00Z&alertId={alertId}
```

## 11. Implementation Order

Follow this order to avoid frontend dead ends:

1. ~~Add alert rule backend persistence and endpoints.~~ Done: persisted `alert_rules`, CRUD/test/effective API, account scoping, gateway route, targeted backend tests.
2. ~~Add alert rule frontend API client.~~ Done: rule CRUD/test/effective client functions and `ruleId` alert filters.
3. ~~Add Alerts page `Rules` tab with create/edit/test.~~ Mostly done: create/test/list/enable/disable/delete, scope controls, notification channel assignment, and telemetry-backed rule test through analytics. Edit UI still needs a polished dedicated form.
4. ~~Add alert inbox redesign and link alerts to rules.~~ MVP done: inbox is now a dense list, rule filters/links work, delivery/trace details are expandable.
5. ~~Add telemetry route/query handling for alert windows.~~ MVP done: alert rows link to `/devices/{deviceId}?tab=telemetry&metric=...&from=...&to=...&alertId=...`, and Device Details applies the metric/time query.
6. ~~Add `Configure alert` from telemetry to rule form.~~ MVP done: Device telemetry links to Rules tab with device, metric, and time window prefilled; rule test uses that window.
7. ~~Move notification channels to Settings/Notifications.~~ Done: notification channel CRUD moved from Alerts to Settings.
8. ~~Refactor navigation and route redirects.~~ Done: normal sidebar is Devices, Alerts, Settings; `/` and `/dashboard` redirect to `/devices`; `/account` redirects to `/settings`.
9. ~~Add Settings page.~~ Done: profile/security summary and notification channel management are available under `/settings`.
10. ~~Add global assistant drawer using existing AI plan/execute APIs.~~ MVP done: authenticated layout has a contextual assistant drawer with route/search context, plan preview, confirmation, execution results, and advanced context hidden by default.
11. ~~Extend AI agent backend tools for rules, alert explanation, telemetry navigation, and demo device creation.~~ MVP done: added rule create/update/test, alert explanation, telemetry route, demo device orchestration, and simulation start/stop tools with sanitizer allowlists and confirmation rules.
12. ~~Convert Add Device modal into wizard.~~ MVP done: Add Device is now a 4-step wizard with source choices, demo preset setup, advanced metadata/source ID controls, and isolated create-device orchestration helper.
13. ~~Split Device Details into components and restructure tabs.~~ MVP done: visible tabs are now Overview, Telemetry, Alerts, Configuration; legacy registry/simulation links map to Configuration; incident summary and tab navigation were extracted into focused components.
14. ~~Add simulator presets/demo flow improvements.~~ MVP done: simulator-service exposes `/simulation/presets`, templates accept a `preset` query, demo YAML enables the selected anomaly server-side, and the Add Device wizard consumes backend preset metadata when available.

If backend rule endpoints are delayed, do not show disabled "Configure alert" buttons as primary UI. Hide them or show a clear "Rule management is not available yet" state.

## 12. Tests And Verification

Backend tests:

- `alert-service`: rule CRUD, validation, account scoping, enable/disable, rule test, alert filter by ruleId.
- `analytics-service`: effective rules fallback, user rule evaluation, built-in rule fallback.
- `ai-agent-service`: new tools sanitize and require confirmation, provider settings remain admin-only.
- `api-gateway`: new routes forward auth headers.

Frontend tests/build:

```powershell
cd frontend
npm run build
```

Backend targeted tests:

```powershell
cd backend
mvn -pl services/alert-service -am test
mvn -pl services/analytics-service -am test
mvn -pl services/ai-agent-service -am test
```

Manual verification:

1. Login redirects to Devices.
2. Sidebar shows Devices, Alerts, Settings for normal user.
3. Add device wizard creates a normal device.
4. Demo device preset creates a simulator config and links to telemetry.
5. Alerts inbox is a dense list/table.
6. Notification channel form is not above the inbox.
7. Rules tab can create, edit, enable/disable, and test a rule.
8. Alert row opens rule details.
9. Alert row opens telemetry around the alert window.
10. Telemetry `Configure alert` opens rule form with metric prefilled.
11. AI assistant drawer can explain an alert and propose a rule, with confirmation required before creating it.
12. AI model settings remain available only to admin.

## 13. Acceptance Criteria

The system improvement is complete when:

- normal user flow no longer starts on Dashboard;
- normal sidebar is not dominated by AI/admin items;
- users can create devices through a guided UI;
- users can create demo telemetry without editing YAML first;
- alerts are explainable by linked rules;
- users can create and tune alert rules;
- alert rules influence future analytics/alert generation or the UI clearly marks them as draft/test-only until backend evaluation is active;
- alerts link to telemetry evidence;
- notification channels live outside the alert inbox;
- AI assistant is available contextually and can plan rule/device/simulator actions;
- mutating AI actions always require confirmation;
- frontend build passes;
- affected backend service tests pass.

## 14. Important Constraints

- Do not remove the existing AI model provider work.
- Do not expose raw API keys.
- Do not trust LLM output; sanitize all tool calls server-side.
- Do not let AI execute mutating actions without confirmation.
- Do not show raw JSON/YAML/IDs as the primary normal-user flow.
- Do not implement configurable dashboards in this iteration.
- Do not break existing alert acknowledge/resolve endpoints.
- Do not break existing simulator YAML advanced flow.
- Preserve tenant/account isolation on every new endpoint.
