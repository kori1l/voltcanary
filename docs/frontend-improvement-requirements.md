# VoltCanary Frontend Improvement Requirements

Status: product requirements for the next frontend iteration  
Date: 2026-06-15  
Based on: `docs/frontend-redesign-audit.md`, current React frontend code, alert API usage, and the current analytics rule implementation.

## 1. Iteration Goal

Make the VoltCanary web UI understandable for a normal operator instead of exposing the shape of backend DTOs. The user should be able to manage devices, inspect telemetry, handle alerts, create demo telemetry, and understand why an alert happened without reading raw JSON, YAML, internal IDs, or implementation labels.

This iteration should not build configurable dashboards. The priority is to remove redundant navigation, simplify device workflows, separate operations from administration, make the AI agent a contextual assistant, and make alerts explainable through rules.

## 2. Current Implementation Snapshot

Current authenticated routes in `frontend/src/app/App.tsx`:

- `/` redirects to `/dashboard`.
- `/dashboard` renders `DashboardPage`.
- `/devices` renders `DevicesPage`.
- `/devices/:deviceId` renders `DeviceDetailsPage`.
- `/alerts` renders `AlertsPage`.
- `/ai-agent` renders `AiAgentPage`.
- `/ai-agent/settings` renders `AiAgentSettingsPage`.
- `/account` renders `AccountPage`.

Current sidebar items in `frontend/src/app/AppLayout.tsx`:

- Dashboard
- Devices
- Alerts
- AI Agent
- Account

Current alerts frontend behavior:

- `AlertsPage` loads alert instances from `/alerts`.
- It loads summary from `/alerts/summary`.
- It loads notification channels from `/alerts/notification-channels`.
- It allows creating, enabling, disabling, and deleting webhook notification channels directly above the alert inbox.
- It displays alert trace fields: `sourceEventId`, `anomalyId`, `riskScoreId`, `ruleId`, calculation window, and calculation input hash.
- It supports acknowledge and resolve actions.
- It does not provide a way to create, edit, enable, disable, test, or inspect alert rules.

Current backend rule state:

- Analytics rules exist as service configuration under `app.analytics.rules`.
- Built-in rules include `POWER_SPIKE`, `CONSUMPTION_GROWTH`, and `LONG_CYCLE`.
- Anomalies and alerts carry `ruleId` and related trace fields.
- There is no visible frontend rule management API in `frontend/src/alerts/alertsApi.ts`.

This means alerts can reference rules, but users cannot manage those rules from the UI yet.

## 3. Product Decisions

1. Remove the current Dashboard from normal user navigation.
   - `/` should open `/devices`.
   - `/dashboard` should redirect to `/devices` until a real Fleet Overview exists.
   - A future Fleet Overview is allowed only if it provides unique operator value: offline devices, stale telemetry, active critical issues, highest risk devices, and clear filtered drilldowns.

2. Keep the normal user navigation small.
   - Primary navigation: `Devices`, `Alerts`, `Settings`.
   - Optional admin-only navigation: `Admin`.
   - Hide `Dashboard`, `AI Agent`, `AI Agent Settings`, and technical demo/dev tools from the normal sidebar.

3. Turn AI Agent into a contextual assistant.
   - The normal user should not navigate to a separate AI Agent page.
   - Add a compact global AI chat entry point, available on key work pages such as Devices, Device Details, and Alerts.
   - Keep provider, model, fallback, prompt, tool, and secret settings in admin-only settings.

4. Make alert rules a first-class product concept.
   - Alerts without editable rules are not complete from a user perspective.
   - Every alert should explain which rule or model produced it.
   - Users need a path from an alert to the rule, and from a telemetry metric to creating or tuning a rule.

## 4. Target Navigation

Normal user:

- `Devices`
- `Alerts`
- `Settings`

Admin user:

- `Devices`
- `Alerts`
- `Settings`
- `Admin`

Do not expose these as normal top-level pages:

- `Dashboard`
- `AI Agent`
- `AI Agent Settings`
- raw simulator/dev tools, unless demo/dev mode is enabled

Route behavior:

- `/` redirects to `/devices`.
- `/dashboard` redirects to `/devices`.
- `/ai-agent/settings` is admin-only.
- `/ai-agent` is either hidden, admin/dev-only, or replaced by the global assistant drawer.

## 5. AI Assistant Requirements

### 5.1 Placement

- Add a global AI assistant button in the lower-right corner of the authenticated app.
- Opening it should show a compact drawer or chat panel.
- The drawer must work on mobile and must not cover primary destructive/confirmation actions.

### 5.2 User Tasks

The assistant should support at least:

- create a normal device;
- create a demo/simulator device;
- configure a telemetry source;
- create or update simulator YAML;
- start or stop demo telemetry generation;
- explain an alert;
- open a device telemetry window for an alert time range;
- suggest an alert rule from a selected metric or alert.

### 5.3 Action Confirmation

The assistant must not silently perform mutating or risky actions. Before creating devices, creating data sources, running simulation, disabling/decommissioning devices, or changing alert rules, it must show a plain-language plan and require confirmation.

Hide these from the normal assistant flow unless Advanced mode is explicitly opened:

- context JSON;
- internal tool names;
- provider/model controls;
- fallback badges such as `rules`, `rule-based planner`, or `LLM fallback`;
- raw payload/result JSON.

## 6. Device Creation Requirements

Replace or improve the current Add Device flow into a guided wizard:

1. Device basics.
   - Name.
   - Type.
   - Location.
   - Optional short description.

2. Telemetry source.
   - Smart plug/API.
   - MQTT sensor.
   - Simulator/demo source.

3. Source setup.
   - For real sources: show practical fields and explain what will be created.
   - For simulator sources: choose a preset and minimal parameters.

4. Review and create.
   - Show the entities that will be created: device, data source, token/config, optional simulator config.

Move technical fields to Advanced:

- metadata JSON;
- source IDs;
- raw protocol values;
- credentials references;
- raw sensor metadata JSON.

Validation errors should explain what to fix in user-facing language.

## 7. Demo And Simulator Requirements

Add creation of demo devices from presets.

Minimum presets:

- `Normal operation`
- `Power spike`
- `Offline window`
- `Consumption growth`

For each preset, the UI should show:

- generated metrics;
- telemetry interval;
- expected alerts or expected no-alert state;
- where the user can view the generated telemetry after starting.

YAML must remain available as an Advanced mode:

- import YAML;
- export YAML;
- validate YAML;
- duplicate config;
- reset to preset;
- edit raw YAML.

The primary simulator flow must not start from an empty YAML editor. Source ID should be selected from configured data sources or created automatically; it should not be a free-form primary field.

## 8. Device Details Requirements

Replace the current overloaded tab model with:

- `Overview`
- `Telemetry`
- `Alerts`
- `Configuration`

Hide or move:

- `Analytics` as a separate tab if it duplicates Overview/Telemetry.
- `Registry`, renamed and folded into `Configuration`.
- `Simulation`, shown as `Demo tools` only for demo/simulator devices or dev/demo mode.

### 8.1 Overview

Overview should be a short summary:

- current status;
- primary issue;
- latest telemetry;
- active alerts;
- risk level;
- recommended next action.

Do not show the full chart grid or repeated risk panels on Overview.

### 8.2 Telemetry

Telemetry is the chart-heavy workspace:

- metric selection;
- time range selection;
- sparse/no-data states;
- event markers;
- links to related alerts;
- human-readable metric names.

For low sample counts, show explicit states:

- `Not enough samples`.
- `Waiting for telemetry`.
- `Sparse data in selected range`.

### 8.3 Device Alerts

Device-scoped alerts should:

- separate active alerts from history;
- group repeated alerts by rule/event type and time window;
- provide actions: `Open telemetry window`, `View rule`, `Acknowledge`, `Resolve`;
- preserve the device filter when linking to the global Alerts page.

### 8.4 Configuration

Configuration should include:

- device metadata;
- telemetry sources;
- metrics/sensor mapping;
- analytics/risk settings;
- lifecycle actions;
- advanced technical details.

Raw JSON should be behind Advanced.

## 9. Alerts And Rules Requirements

### 9.1 Alert Inbox

The Alerts page should be an incident inbox, not a notification channel configuration page.

Requirements:

- use a dense table/list by default instead of large repeated cards;
- support filters by status, severity, device, location, rule, and time range;
- support sorting by severity, age, device, location, status, and rule;
- hide technical IDs in expandable details;
- keep delivery attempts available in details;
- provide quick actions: acknowledge, resolve, view device, view telemetry window, view rule;
- group repeated alerts where useful;
- move notification channels out of the main inbox flow.

Notification channels should live in `Settings` or inside a secondary Rules/Notifications area, not above the alert list.

### 9.2 Rules UI Is Required For Alert Explainability

Alert rules are required, not optional polish.

Current state:

- Alerts display `ruleId`.
- Analytics emits rule-backed anomalies.
- Built-in rules are configured through backend configuration.
- The frontend has no rule list, create rule flow, edit rule flow, or enable/disable rule action.

Required user-facing capability:

- list rules;
- create a rule;
- edit a rule;
- enable/disable a rule;
- set rule scope: fleet, location, device type, or individual device;
- set conditions and thresholds;
- set severity mapping;
- set cooldown/deduplication behavior;
- attach notification routing;
- test a rule against recent telemetry;
- open a rule from an alert;
- create a rule from a telemetry metric or selected chart event.

Minimum acceptable IA:

- Add a `Rules` tab inside `Alerts`.

Preferred longer-term IA:

- Add a dedicated `Rules` section if alert and analytics policy configuration grows beyond alerting.

### 9.3 Backend Contract Needed For Rules

The frontend cannot fully implement Rules UI with the current visible alert API alone. A rule management contract is needed.

Minimum API shape to support the UI:

- `GET /alert-rules`
- `GET /alert-rules/{id}`
- `POST /alert-rules`
- `PUT /alert-rules/{id}`
- `POST /alert-rules/{id}/enable`
- `POST /alert-rules/{id}/disable`
- `POST /alert-rules/test`

Minimum rule fields:

- `id`
- `name`
- `description`
- `enabled`
- `ruleType`
- `scope`
- `metric`
- `condition`
- `threshold`
- `severity`
- `cooldown`
- `deduplicationWindow`
- `notificationChannelIds`
- `createdAt`
- `updatedAt`
- `version`

If backend rule persistence is out of scope for this iteration, the frontend should still avoid presenting `Configure alert` as a disabled dead end. Use one of these alternatives:

- hide the action until rules are supported;
- show a clear "Rule management is not available yet" state;
- link to read-only built-in rule details generated from the analytics configuration contract.

## 10. Settings And Account Requirements

Move Account into Settings.

Settings should include:

- profile;
- security;
- email verification status and action;
- notification channels;
- workspace/account metadata;
- technical details disclosure for internal IDs.

Admin-only settings should include:

- AI provider configuration;
- model configuration;
- secret rotation;
- fallback mode;
- available AI capabilities/tools;
- AI action audit log.

## 11. Visual And Interaction Requirements

- Reduce backend DTO editor feel.
- Separate primary, secondary, and destructive actions visually.
- Move rare destructive actions into a lifecycle menu.
- Write errors in user-facing language.
- Keep technical fields only where they are needed.
- Make lists and tables scannable with sorting, filtering, and compact density.
- Empty states must lead to a real next action.
- Use human-readable metric labels such as `Active power`, `Current`, and `Energy`, not raw keys such as `activePowerW`.

## 12. Implementation Priorities

### P0

- Remove `Dashboard` from normal navigation.
- Redirect `/` and `/dashboard` to `/devices`.
- Remove `AI Agent` from normal navigation.
- Add a global AI assistant entry point.
- Restrict AI settings to admin-only access.
- Restructure device tabs to `Overview`, `Telemetry`, `Alerts`, `Configuration`.

### P1

- Convert Add Device into a guided wizard.
- Add demo/simulator device creation from presets.
- Keep YAML import/export/validate as Advanced.
- Add simulator action links to generated telemetry.
- Move notification channels out of the alert inbox.

### P2

- Add the Rules UI and the required backend/API contract.
- Link alerts to rules in the UI.
- Add create/edit rule from alert and telemetry contexts.
- Improve the chart investigation modal.
- Add repeated alert grouping.
- Add first-run demo data or one-click demo setup.

### P3

- Revisit Fleet Overview only when it has unique operational data and actions.
- Consider configurable dashboards as a separate future product, not part of this iteration.

## 13. Definition Of Done

- A normal user lands on Devices and does not see a redundant Dashboard.
- A normal user can create a device without writing raw JSON/YAML.
- A normal user can create a demo device from a preset and immediately view telemetry.
- YAML remains available in Advanced mode.
- AI is available as a contextual assistant and asks for confirmation before changes.
- AI provider/model settings are admin-only.
- Device Details no longer repeats the same concepts across multiple tabs.
- Alerts explain what happened and provide links to telemetry and rule context.
- Users can create or edit alert rules, or the UI clearly states that rule management is not yet supported.
- Notification channels no longer interrupt the alert inbox workflow.
- Technical IDs, raw payloads, and backend-oriented labels do not dominate the normal user interface.
