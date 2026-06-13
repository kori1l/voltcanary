# VoltCanary Frontend Redesign Audit

Status: draft for discussion
Date: 2026-06-13

This document captures current frontend UX/product issues before redesign work starts. It is intentionally written as an audit, not as a final implementation plan.

## Main Product Questions

1. What is the primary daily workflow?
   - Monitor fleet health?
   - Investigate one problematic device?
   - Configure devices and ingestion?
   - Tune alerting and analytics rules?
   - Use the AI agent as the main operator interface?

2. Who is the main user?
   - Home/lab user with a few devices.
   - Operations user monitoring many assets.
   - Admin configuring integrations and alerting.
   - Developer/test user validating the platform.

3. What should be visible immediately after login?
   - A dashboard only makes sense if it gives a unique operator overview and clear next actions.
   - If it mostly repeats Devices and Alerts, the app should probably start on Devices or a redesigned Fleet Overview.

## Navigation Issues

### Dashboard

Current issue:

The Dashboard page mostly repeats data from other pages:

- total devices
- active/resolved alerts
- latest telemetry for a few devices
- link to alerts

This does not yet justify a separate top-level navigation item.

Possible directions:

1. Remove Dashboard from top navigation.
   - Start authenticated users on Devices.
   - Keep summary widgets inside Devices or Alerts.

2. Rename Dashboard to Fleet Overview and make it operationally useful.
   - Show fleet health by location/type/status.
   - Show devices needing attention.
   - Show stale telemetry / offline sources.
   - Show top risks and newest anomalies.
   - Make every card lead to a filtered list.

3. Make dashboards user-configurable.
   - Saved widgets.
   - Saved filters.
   - Per-user layout.
   - Useful later, but probably too heavy for the current product stage.

Initial recommendation:

Do not build configurable dashboards yet. Either remove Dashboard or turn it into a focused Fleet Overview with clear operator actions.

### Top-Level Navigation

Current navigation:

- Dashboard
- Devices
- Alerts
- AI Agent
- Account

Missing or unclear:

- No obvious place for alert rules.
- No obvious place for integrations/data sources at fleet level.
- Telemetry exists only inside device details.
- Simulation is hidden inside device details, although it is important for demo/dev workflows.

Possible target navigation:

- Fleet
- Alerts
- Rules
- AI Agent
- Settings

Alternative simpler target:

- Devices
- Alerts
- AI Agent
- Settings

## Device Details Issues

Current device details tabs:

- Overview
- Telemetry
- Registry
- Analytics
- Alerts
- Simulation

Current issue:

The device page is doing too much. Overview and Telemetry feel similar because both present device health, metrics, telemetry-derived events, and chart context. Analytics also overlaps with telemetry because risk, anomalies, predictions, and recommendations are derived from telemetry.

Specific problems:

- Too many tabs for a single device page.
- The boundary between Overview, Telemetry, and Analytics is unclear.
- Registry/configuration actions compete with monitoring/investigation actions.
- Alerts inside device details duplicate the global Alerts page.
- Simulation is useful, but it is a dev/demo control surface mixed into the operator view.
- The page has a high cognitive load: charts, analytics, config JSON, sources, sensors, simulation YAML, alerts, and lifecycle actions are all nearby.

Possible redesign:

1. Device Overview
   - Identity: name, type, location, status.
   - Current health: latest telemetry, risk, active alert count.
   - Top 3 actions: view telemetry, configure sources, acknowledge alerts.
   - Recent events/timeline.

2. Telemetry
   - Charts and time range.
   - Metric selection.
   - Event markers.
   - Data quality/staleness.
   - Raw readings only if needed.

3. Alerts
   - Device-scoped alert list.
   - Rule that generated each alert.
   - Acknowledge/resolve actions.

4. Configuration
   - Device metadata.
   - Data sources.
   - Sensors.
   - Analytics settings.
   - Lifecycle actions.

5. Simulation
   - Keep visible only in dev/demo mode, or move to a separate Tools/Simulation area.

Initial recommendation:

Collapse the current six tabs into four user-facing sections:

- Overview
- Telemetry
- Alerts
- Configuration

Keep Simulation behind a dev/demo affordance unless the product explicitly targets simulation workflows.

## Alerts Issues

Current issue:

The Alerts page allows users to review alerts and configure notification channels, but it does not answer the most important operator question: "Why did this alert happen, and how do I change the rule?"

Missing concepts:

- Alert rules.
- Rule creation.
- Rule editing.
- Rule enable/disable.
- Rule scope: fleet, location, device type, individual device.
- Thresholds and conditions.
- Severity mapping.
- Cooldown/deduplication.
- Notification routing by rule.
- Test rule against recent telemetry.

Current confusing state:

- Alerts exist.
- Alert details show a `ruleId`.
- There is no visible UI to create or manage that rule.
- Notification channels are on the Alerts page, but channels are not the same thing as rules.

Possible redesign:

1. Alerts page
   - Focus on current and historical alert instances.
   - Filters, grouping, acknowledge/resolve, delivery attempts.

2. Rules page
   - Create/edit rules.
   - Enable/disable rules.
   - Set scope.
   - Set conditions.
   - Set severity.
   - Attach notification channels.
   - Preview/test rule.

3. Notification channels
   - Move to Settings or keep as a secondary tab under Rules/Alerts.

Initial recommendation:

Add a top-level Rules section, or add two tabs inside Alerts:

- Alert Inbox
- Rules

For current scope, `Alerts -> Rules` is probably enough.

## Information Architecture Problems

Current issue:

The app mixes four different modes without clear separation:

- Monitoring: what is happening now?
- Investigation: why did this happen?
- Configuration: how is this asset wired?
- Administration: users, providers, channels, system settings.

This makes pages feel busy and makes it hard to decide where a feature belongs.

Proposed model:

- Fleet/Devices: inventory and status.
- Device Detail: investigation and per-device configuration.
- Alerts: alert instances and response workflow.
- Rules: alert/analytics policy configuration.
- AI Agent: assisted operations.
- Settings: account, integrations, notification channels, providers.

## Visual And Interaction Issues To Review

Potential issues to audit in the UI:

- Dense pages use many panels and cards; hierarchy may be weak.
- Buttons and forms may not consistently communicate primary vs secondary actions.
- Empty states exist but often do not lead to the next best action.
- Some technical fields are exposed directly, such as JSON analytics config and YAML simulation config.
- Tables/lists should support scanning and filtering before adding more visual decoration.
- Device cards and dashboard cards may duplicate the same metrics in different layouts.
- Error messages often expose raw backend wording rather than guiding recovery.

## Screenshot-Based UX Findings

These findings are based on the current frontend screenshots captured during local Docker usage.

### Global Layout

Observed issues:

- The left sidebar consumes a fixed wide column, while many pages still leave large unused horizontal and vertical areas.
- The sidebar active state is visually heavy compared with the actual content hierarchy.
- Top-level navigation does not reflect the actual work model. For example, Account appears as a main operational section even though most users will not visit it often.
- Page headers are simple but often do not explain the next useful action.
- Many pages have a large empty lower half on desktop, making the UI feel unfinished even when data exists.
- The application uses many bordered panels and cards with similar visual weight, so important information does not stand out.

Possible improvements:

- Rebalance the layout toward denser operational content.
- Make the sidebar more compact or add section grouping.
- Move rarely used account/admin actions into Settings.
- Use stronger page-level hierarchy: primary status, primary action, secondary tools.
- Reduce repeated card/panel chrome where it does not add meaning.

### Devices Page

Observed issues:

- Device cards are visually repetitive and large relative to the amount of information shown.
- Device cards show status, score, power, and timestamp, but do not clearly communicate what the operator should do next.
- The meaning of "No score", "Low", "No data", and "No telemetry" is unclear. These labels mix risk state, scoring availability, and data availability.
- Devices are grouped by location, but location groups can leave large empty horizontal areas.
- The page has action buttons for Refresh, Import CSV, Export CSV, and Add device, but their priority feels similar.
- Search and filters are useful, but there is no obvious view switch between cards/table/map/list.
- Some device names in screenshots look like test artifacts (`Test`, `test3`, `Teast2`), which makes the product feel less intentional.
- Device telemetry values use raw metric keys or compact labels that may not be user-friendly.

Possible improvements:

- Add a list/table mode for operational scanning.
- Clarify health terminology:
  - `Healthy`
  - `Needs attention`
  - `No telemetry`
  - `Analytics pending`
  - `Offline`
- Make device cards action-oriented:
  - View telemetry
  - Investigate alert
  - Configure source
- Improve empty/no-data states per device.
- Add bulk cleanup or hide demo/test devices for non-demo use.
- Make location grouping collapsible.

### Add Device Drawer

Observed issues:

- The drawer mixes device creation, location creation, metadata editing, and data source creation in one flow.
- "New location" appears next to an existing location selector, but it is unclear when it applies.
- Metadata JSON is too technical for a primary create-device flow.
- "Data source" options use implementation terms such as Manual REST/API, Simulator, MQTT without explaining practical consequences.
- The form does not clearly show which fields are required.
- There is no step-by-step confirmation of what will be created.

Possible improvements:

- Split into a wizard:
  1. Device basics.
  2. Location.
  3. Telemetry source.
  4. Review.
- Hide advanced metadata behind an expandable Advanced section.
- Replace source labels with user-facing choices:
  - Smart plug/API
  - MQTT sensor
  - Simulator/demo source
- Show what happens after creation: device, data source, optional token/config.

### Import Devices Drawer

Observed issues:

- The import drawer is mostly a blank panel with a raw textarea.
- The required CSV format is shown as plain text but there is no downloadable template inside the flow.
- The UI does not explain whether locations must already exist or how `locationId` should be found.
- Validation feedback is not visible in the screenshot, and the user may not understand how to fix import errors.
- Import is presented as a high-level action but the UX is developer-oriented.

Possible improvements:

- Provide a sample CSV and a "Download template" action.
- Support location names in addition to raw `locationId`.
- Add inline validation preview before import.
- Show parsed rows, errors, and successful rows separately.
- Consider moving CSV import under an Advanced or Bulk operations menu.

### Dashboard Page

Observed issues:

- The Dashboard duplicates counts and latest telemetry already available elsewhere.
- It shows "Active alerts 39" but the "Current Attention" panel says "0 high priority alerts need review" while still showing an acknowledged warning. This is confusing.
- "Latest Signals" is a raw list of readings from first devices, not a prioritized operational view.
- Raw metric labels such as `Activepowerw`, `Currenta`, and `Energywh` are hard to scan and look unpolished.
- The page has large empty space below the two main panels.
- "Resolved alerts" as a top metric may be less important than "offline devices", "stale telemetry", or "new critical alerts".

Possible improvements:

- If Dashboard remains, make it an exception-focused Fleet Overview.
- Replace raw latest telemetry with prioritized cards:
  - Devices with active alerts.
  - Devices with stale telemetry.
  - Highest risk devices.
  - Recent anomalies.
- Make metric labels human-readable:
  - Active power
  - Current
  - Energy
- Align top metrics with actual operator decisions.

### Alerts Page

Observed issues:

- The alert summary says 38 open, 1 acknowledged, 5 resolved, 0 critical, but the main content is still dominated by repeated "Power spike detected" cards.
- Alert cards expose technical IDs: source, anomaly, input. These are not useful as primary information.
- Rule is shown as `POWER_SPIKE`, but there is no path to view or edit the rule.
- Cards are large and repetitive, making alert triage hard.
- The three-column Open/Acknowledged/Resolved layout works poorly when counts are uneven.
- Notification channel creation is placed above the alert inbox, which interrupts the incident response workflow.
- The "Most important" panel is visually detached and does not explain why that alert is most important.
- Resolution reason text can contain low-quality/free-form input and is displayed prominently without structure.

Possible improvements:

- Use a dense alert table/list by default, with expandable details.
- Put technical IDs behind "Details".
- Add direct actions:
  - View device
  - View telemetry at alert window
  - View/edit rule
  - Acknowledge
  - Resolve
- Move Notification Channels to Settings or Rules.
- Replace status columns with filters/tabs unless the kanban model is intentional.
- Add severity, age, device, location, and rule as sortable columns.

### Account Page

Observed issues:

- Account is a top-level navigation item but the page mostly contains profile metadata and security controls.
- Internal IDs are prominent: user ID and device account ID.
- Workspace totals repeat data from Dashboard/Devices/Alerts.
- The page shows "Email verification: Not verified", but the consequences and next action are not clear enough.
- Security actions are mixed with workspace summary.
- The refresh button on Account is probably not a meaningful primary action.

Possible improvements:

- Move Account under Settings.
- Hide internal IDs behind a "Technical details" disclosure.
- Make verification state actionable.
- Separate Profile, Security, Sessions, and Workspace/Admin sections.

### AI Agent Page

Observed issues:

- The AI Agent screen feels like a developer tool rather than an operator assistant.
- It exposes Context JSON directly in the main flow.
- Tool names are implementation-oriented: Create Device, Create Data Source, Create Simulator Config, Validate Simulator YAML.
- The right panel is mostly empty until a plan exists.
- Model/provider controls are prominent even though normal users may not understand them.
- The current fallback badge ("rules / rule-based planner / LLM fallback") is technical and unclear.
- Example prompts are useful, but they are displayed as plain links below the main form rather than guiding task selection.

Possible improvements:

- Turn the page into task-first flows:
  - Create a demo device.
  - Connect telemetry.
  - Investigate an alert.
  - Generate a simulator.
- Hide Context JSON behind Advanced.
- Present tools as capabilities, not internal tool names.
- Make the plan preview the center of the page after a request.
- Move model settings to Settings, keeping only a compact model indicator in the agent page.

### Forms And Technical Inputs

Observed issues:

- JSON/YAML/CSV inputs are exposed in primary user flows.
- Several fields are not self-explanatory: credentials ref, metadata JSON, context JSON, source IDs, rule IDs.
- Many forms look like backend DTO editors rather than guided product workflows.

Possible improvements:

- Use structured controls for common cases.
- Keep raw JSON/YAML/CSV for advanced users only.
- Add validation, previews, and templates.
- Rename technical fields into user-facing concepts.

### Device Detail: Header And Global Actions

Observed issues:

- Device-level actions are always visible: Refresh device, Disable, Decommission, Recalculate risk.
- Destructive or rare actions sit next to routine actions with similar visual weight.
- Recalculate risk is visually the strongest action, but it may not be the most common user task.
- The header does not clearly separate "monitoring actions" from "administrative/lifecycle actions".
- The device title area is minimal; it does not summarize the device's current operational state well enough.

Possible improvements:

- Make primary contextual action depend on the current state:
  - Investigate alert.
  - View telemetry.
  - Configure source.
- Move Disable and Decommission behind a more deliberate lifecycle menu.
- Keep Refresh as a small icon/action, not a page-level dominant button.
- Show a compact status strip in the header:
  - Health.
  - Active alerts.
  - Last telemetry age.
  - Source status.
  - Risk.

### Device Detail: Overview

Observed issues:

- Overview already includes telemetry monitoring, chart cards, risk analysis, "why this matters", recommended action, and recent events.
- This makes Overview feel like a second Telemetry/Analytics page.
- The top incident banner is useful, but below it the page becomes dense and repetitive.
- "Mark as checked" appears in multiple places and its effect is not obvious.
- The risk score is repeated in different panels: top metrics, risk analysis card, sidebar, analytics tab.
- "Samples analyzed" can show very low sample counts, but the UI still presents confident-looking conclusions.

Possible improvements:

- Make Overview an executive summary only:
  - Current status.
  - Primary issue.
  - Most likely reason.
  - Recommended next action.
  - Links into Telemetry, Alerts, Configuration.
- Do not show full chart grids on Overview.
- Add data confidence and sample count as a warning when low.
- Rename "Mark as checked" to a clearer action:
  - Acknowledge device state.
  - Mark reviewed.
  - Snooze warning.
  - Clear current recommendation.
- Avoid repeating the same risk score in multiple nearby places.

### Device Detail: Telemetry

Observed issues:

- Telemetry cards are large, but actual plotted data often occupies a very small part of the chart.
- The charts look sparse when there are few samples. This can make the UI feel broken even when the data is technically correct.
- Several metrics show tiny lines at the far right, leaving most of the chart blank.
- Metric cards repeat Latest/Avg/Min/Max/Alerts for every metric, which consumes much space.
- The "Analyze" button appears on every metric, but it is unclear what it does or how it differs from the Analytics tab.
- "Metrics 4" is both a pill and a control; the wording is not clear.
- "Show points" is a technical chart option exposed prominently.
- Event timeline exists, but it is separated from the chart interactions and does not feel like the main investigation path.
- Raw metric keys appear in places, for example `activePowerW`, which weakens polish.

Possible improvements:

- Add a data density state:
  - Not enough samples.
  - Waiting for telemetry.
  - Sparse data in selected range.
- Let the chart adapt to sparse data:
  - Use dots/markers by default for low sample counts.
  - Show a compact sparkline plus a clear "only N samples" note.
  - Avoid misleading full-width empty charts.
- Make one focused chart the primary view, with secondary metrics as compact rows.
- Replace per-card Analyze buttons with one selected-metric detail panel.
- Rename "Metrics 4" to "Choose metrics".
- Hide "Show points" under chart settings unless useful for debugging.
- Use human-readable metric names everywhere.
- Link event timeline items to the exact chart window and marker.

### Chart Analysis Modal

Observed issues:

- The modal is very large, but the chart itself is still functionally basic.
- A lot of vertical space is spent on the chart, while the actual useful analysis is pushed below the fold.
- The toolbar has many pill-like indicators: `15m`, `4 samples`, `1 metric events`, `1 total events`, `Points off`, `180 display points`, `Brush zoom`. Some are status, some are controls, and they look too similar.
- "Points off" is confusing: it sounds like data points are missing or disabled, not that point markers are hidden.
- "180 display points" is an implementation detail; users care about whether data is complete, sampled, or downsampled.
- Brush zoom exists, but there are no obvious zoom reset, pan, compare range, or jump-to-event controls.
- With only 4 samples, the line visually collapses into a spike/drop and can be misleading without explicit sparse-data treatment.
- The y-axis can include odd values such as `-32 W`, which is confusing for active power unless negative power is meaningful.
- The selected event marker is visually present, but there is no clear hover/click detail panel tied to a specific point.
- Thresholds, analysis, related signals, and event timeline are useful, but they are below the chart and require scrolling.
- The `Configure alert` button is disabled, which exposes a missing capability rather than guiding the user.
- Related signals are shown as text summaries, but the user cannot compare them visually against the main metric in the same chart.
- The modal behaves like a report, not like an investigation tool.

Missing graph-analysis capabilities:

- Hover tooltip with timestamp, value, threshold state, and event/rule context.
- Click event to pin details.
- Zoom reset and visible selected range.
- Pan or keyboard navigation across time.
- Compare previous period / baseline period.
- Overlay related metrics such as voltage/current/energy on the same timeline.
- Toggle thresholds, baseline, event markers, and raw points.
- Explain sampling/downsampling and data quality.
- Export/share the selected graph window.
- Jump from chart event to alert instance and rule configuration.
- Create or edit an alert rule from the selected threshold/event.

Possible improvements:

- Split the modal into a clear investigation layout:
  - Left/main: chart with direct interactions.
  - Right: selected event/point details, likely reason, recommended action.
  - Bottom: thresholds, related signals, event timeline.
- Move important analysis above the fold.
- Replace mixed toolbar pills with grouped controls:
  - Range: 15m / 1h / 6h / 24h / custom.
  - Display: points, thresholds, events, related signals.
  - Tools: zoom, reset zoom, compare, export.
- Rename technical indicators:
  - `4 samples` -> `Sparse data: 4 samples`.
  - `180 display points` -> hide unless downsampling is active.
  - `Points off` -> `Point markers hidden`.
- Add an explicit sparse-data warning when sample count is low.
- Clamp or explain impossible-looking axis ranges for metrics such as active power.
- Make `Configure alert` functional, or remove it until rules are implemented.
- Add a "View alert" or "View rule" action from the selected event.
- Allow related signals to be overlaid or opened in synchronized mini-charts.
- Add a clear "Back to telemetry" or close affordance that does not rely only on top-right Close.

### Chart Analysis Product Direction

Current chart analysis tries to do three things at once:

- show a larger chart;
- explain an anomaly;
- expose thresholds and related signals.

The concept is good, but the flow should become an investigation workspace rather than a bigger card.

Recommended target flow:

1. User clicks a chart event or alert.
2. The analysis view opens with the event pinned.
3. The chart centers on the event window.
4. The side panel explains:
   - what happened;
   - which rule/model detected it;
   - what changed before/after;
   - related signals;
   - recommended action.
5. User can:
   - acknowledge/resolve the alert;
   - adjust or create a rule;
   - compare to baseline;
   - export/share the evidence.

### Device Detail: Analytics

Observed issues:

- Analytics repeats concepts already visible on Overview and Telemetry: risk score, reasons, selected metric, chart, threshold, events.
- "0% confidence" appears next to a strong statement: "Anomaly risk is elevated". This undermines trust.
- The page says anomaly score and degradation are `0 / 100`, while the overall risk card says `30 / 100 - Low`. The relationship between these numbers is unclear.
- "Model unknown" is visible but not actionable.
- The chart annotation labels are visually strong, but the user cannot configure the threshold/rule from here.
- "Select all" and "Reset" are ambiguous in the analytics context.
- The main chart is oversized and requires scrolling to see the table and explanation.

Possible improvements:

- Decide whether Analytics is a separate page or a detail mode within Telemetry.
- If kept separate, make it about explanation and model outputs:
  - Why did risk change?
  - What evidence was used?
  - Which rule/model produced this?
  - How confident is it?
  - What should the user do next?
- If confidence is 0%, do not present definitive language.
- Explain the relationship between risk score, anomaly score, degradation, severity, and rule events.
- Add "View/edit threshold rule" from the chart.
- Rename ambiguous controls:
  - Recalculate selected metric.
  - Reset metric selection.
- Keep the chart smaller and put explanation/evidence above it.

### Device Detail: Alerts Tab

Observed issues:

- Device alerts are shown as a table-like list, which is more scannable than global alert cards, but the rows still repeat similar alerts many times.
- There is no grouping by rule/event type or time window.
- Each row has only Acknowledge as an action; it does not offer "View telemetry at this time" or "View rule".
- The page says "12 shown from 12 alerts for this device", but does not explain whether this is a serious current state or historical backlog.
- Counts Open/Acknowledged/Resolved are useful, but there is no severity split or trend.
- The "All alerts" button duplicates global navigation without preserving useful context.

Possible improvements:

- Group repeated alerts:
  - Power spike, 5 occurrences.
  - Consumption growth, 3 occurrences.
  - Risk score changed, 4 occurrences.
- Add per-alert investigation actions:
  - Open telemetry window.
  - View rule.
  - View related anomalies.
- Show current active alerts separately from history.
- Add bulk acknowledge for repeated non-critical warnings.
- Keep a link to global Alerts with device filter preserved.

### Device Detail: Registry / Configuration

Observed issues:

- The Registry tab combines data sources, sensors, and analytics config, which are related but have different mental models.
- The source token is shown inline after rotation. This is useful, but it needs stronger one-time-secret handling.
- Data source fields use technical labels: External ID, Source type, Protocol.
- Sensor creation requires type, unit, metric mapping, and metadata JSON without guidance.
- "No sensors configured" appears despite telemetry existing, which can confuse users about whether sensors are required.
- Analytics config is raw JSON in a tiny textarea, leaving most of the panel empty.
- Enabled checkbox for analytics config is detached from the JSON editor and has unclear consequences.

Possible improvements:

- Rename Registry to Configuration.
- Split into sections with clearer purposes:
  - Telemetry sources.
  - Metrics/sensors mapping.
  - Analytics rules/settings.
  - Advanced technical details.
- Treat rotated tokens as one-time secrets:
  - Show copy button.
  - Show "visible once" warning.
  - Avoid placing token in a passive green banner only.
- Replace Analytics config JSON with structured controls for common settings.
- Keep raw JSON under Advanced.
- Explain whether sensors are optional metadata or required mapping.

### Device Detail: Simulation

Observed issues:

- Simulation is a full developer/demo configuration console inside a normal device page.
- It exposes many numeric fields without units/context beyond small labels.
- Running state is visible, but the page does not clearly explain where generated telemetry will appear or whether the simulator is connected to the current data source.
- Source ID can be arbitrary-looking (`sdfdf`), which makes the setup feel fragile.
- Anomaly toggles are useful, but their relationship to generated alerts/rules is not explained.
- Run history is plain text rows with little diagnostic value.
- Advanced YAML is hidden, which is good, but the rest of the page is still highly technical.

Possible improvements:

- Move Simulation to a Demo Tools area, or hide it behind a dev/demo mode.
- If kept on device page, present it as "Generate demo telemetry" rather than "Simulation".
- Provide presets:
  - Normal operation.
  - Power spike.
  - Offline window.
  - Consumption growth.
- Explain expected result:
  - Telemetry generated every N seconds.
  - Alerts expected after X minutes.
  - View generated telemetry.
- Make run history actionable:
  - View telemetry.
  - View logs.
  - Re-run.
  - Stop.
- Validate Source ID against configured data sources instead of free-form input where possible.

### Device Detail: Page Structure Recommendation

Based on screenshots, the current six-tab model should be replaced with a clearer task model:

1. Overview
   - Summary, current issue, next action.
   - No full chart grid.

2. Telemetry
   - Charts, events, data quality, selected metric details.

3. Alerts
   - Current active alerts, grouped history, investigation links.

4. Configuration
   - Sources, sensors/metric mapping, analytics settings.

5. Demo Tools
   - Optional simulation, hidden unless demo/dev mode is enabled.

This would remove most overlap between Overview, Telemetry, Analytics, Registry, and Simulation while keeping the same underlying functionality.

## Data And Demo Issues

Current issue:

Demo devices can exist without meaningful telemetry. This makes Dashboard, Telemetry, Analytics, and Alerts look incomplete even when inventory is populated.

Needed for demo-quality frontend:

- Seed devices.
- Seed recent telemetry.
- Seed at least one normal device and one anomalous device.
- Seed alerts linked to understandable rules.
- Seed recommendations and risk states, or make analytics easy to trigger from UI.

## Proposed Redesign Tracks

### Track 1: Navigation Cleanup

Goal:

Make the app easier to understand without deep backend changes.

Work:

- Decide whether Dashboard remains.
- Rename or remove redundant pages.
- Simplify device detail tabs.
- Move notification channels out of the main alert inbox flow.

### Track 2: Alert Rules UX

Goal:

Make alerts explainable and configurable.

Work:

- Add rule list.
- Add rule create/edit form.
- Add rule scope and threshold UI.
- Link every alert to its rule.
- Add "create rule from device metric" flow.

### Track 3: Device Detail Redesign

Goal:

Turn device details into a clear investigation page.

Work:

- Redesign Overview around current state and next actions.
- Make Telemetry the single chart-heavy area.
- Merge Registry and Analytics config into Configuration.
- Keep device Alerts as a focused scoped inbox.

### Track 4: Demo Data And First-Run Experience

Goal:

Make the app feel alive immediately after login.

Work:

- Seed telemetry for demo devices.
- Seed alert/rule examples.
- Add first-run guidance only where it triggers real actions.

## Open Questions For Discussion

1. Should Dashboard be removed, renamed to Fleet Overview, or kept as a future configurable dashboard?
2. Should Rules be a top-level page or a tab inside Alerts?
3. Should Simulation be visible to normal users?
4. Should telemetry and analytics be separate tabs, or should analytics become annotations inside Telemetry?
5. What is the single most important first screen after login?
6. Should Settings absorb Account, AI provider settings, notification channels, and integrations?

## Initial Product Direction

Recommended direction for the next iteration:

1. Replace Dashboard with Fleet Overview, or redirect `/dashboard` to `/devices` until Fleet Overview is genuinely useful.
2. Redesign Device Detail into Overview, Telemetry, Alerts, Configuration.
3. Add Alert Rules UI.
4. Move notification channels into Settings or Rules.
5. Add seeded telemetry and alert examples so the redesigned screens have meaningful content.
