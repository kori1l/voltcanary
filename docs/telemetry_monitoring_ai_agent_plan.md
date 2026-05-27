# AI Agent Task Spec: Telemetry Monitoring & Analytics Redesign

## 0. Context

We already have a telemetry screen with several metric charts, a metric selector, time range controls, and a modal opened from a single chart. The current UI shows graphs, but it does not yet feel like a serious monitoring and analytics tool.

The goal is to redesign and extend this feature so that a user can quickly answer:

1. Is the device healthy now?
2. Which metric has a problem?
3. When did the problem happen?
4. How serious is it?
5. What other signals changed at the same time?
6. What is the likely reason?
7. What action should the user take next?

This document is intended to be given directly to an AI coding agent.

---

## 1. Main Goal

Build a proper telemetry monitoring and analytics module, not just a set of raw charts.

The final UX should have two main modes:

1. **Telemetry Overview**  
   A dashboard for quickly understanding the current device state, important metrics, anomalies, and alerts.

2. **Metric Detail / Analysis View**  
   A detailed view for one metric with a large chart, statistics, thresholds, event markers, anomaly explanation, and related signals.

---

## 2. Core Product Requirements

### 2.1 Overview screen must show

- Device health status: `Normal`, `Warning`, `Critical`, `Stale`, `No data`.
- Last sample timestamp.
- Data quality indicator.
- Active alerts count.
- Selected time range.
- Selected metrics.
- Metric cards with mini charts.
- Event timeline under charts.
- Clear visual difference between normal and problematic metrics.

### 2.2 Each metric card must show

For each metric card:

- Metric name.
- Current/latest value.
- Status badge.
- Unit.
- Min value in selected range.
- Max value in selected range.
- Average value.
- Peak value if applicable.
- Number of anomalies/alerts.
- Small chart.
- Button/link to open details.

Example:

```text
Active power                         Warning
Latest: 2.34 W                       Peak: 138 W
Avg: 12.8 W                          Alerts: 2
[small chart]
[Analyze]
```

### 2.3 Metric detail view must show

The current modal is not good enough. Replace it with either:

- a fullscreen detail page, or
- a large right-side drawer, or
- a large centered modal where the chart is the main visual element.

The detail view must include:

- Metric name.
- Status badge.
- Time range.
- Sample count.
- Latest value.
- Average.
- Min.
- Max.
- P95/P99 if easy to compute.
- Stability score if available.
- Large chart.
- Thresholds section.
- Events related to this metric.
- Related signals/correlation section.
- Recommended action section.

Example layout:

```text
Voltage                                      Normal
720 samples · 1h · May 20, 3:25 PM — 4:25 PM

Latest     Avg       Min       Max       Stability
230 V      230.1 V   228.2 V   231.8 V   98.7%

[large chart]

Thresholds
Normal range: 228–232 V
Warning: <225 V or >235 V
Critical: <220 V or >240 V

Events
No critical events in selected range.

Analysis
Voltage is stable. No action required.
```

---

## 3. Architecture Overview

### 3.1 Frontend architecture

Create or refactor the telemetry feature into the following logical modules:

```text
features/telemetry/
  pages/
    TelemetryOverviewPage.tsx
    MetricDetailPage.tsx             # optional if detail is a route

  components/
    TelemetryHeader.tsx
    TelemetryToolbar.tsx
    MetricSelector.tsx
    HealthSummaryCards.tsx
    MetricCard.tsx
    MetricGrid.tsx
    MetricDetailDrawer.tsx           # optional if drawer is used
    MetricDetailPanel.tsx
    MetricStatsBar.tsx
    TelemetryChart.tsx
    ChartTooltip.tsx
    EventMarker.tsx
    EventTimeline.tsx
    ThresholdLegend.tsx
    ThresholdEditor.tsx              # optional MVP+ feature
    CorrelationPanel.tsx
    RecommendationPanel.tsx
    EmptyState.tsx
    LoadingState.tsx
    StaleDataBanner.tsx

  model/
    telemetry.types.ts
    telemetry.constants.ts
    telemetry.mappers.ts
    telemetry.utils.ts
    telemetry.analytics.ts

  api/
    telemetryApi.ts

  hooks/
    useTelemetryOverview.ts
    useMetricSeries.ts
    useMetricDetail.ts
    useMetricSelection.ts
    useTelemetryTimeRange.ts
```

Do not keep all logic inside one large component. The page component should only orchestrate data loading and layout.

### 3.2 Backend architecture

If the backend already provides telemetry data, create an adapter layer on the frontend and map existing responses to the new frontend model.

If backend changes are allowed, introduce these backend responsibilities:

1. Store or read telemetry samples.
2. Aggregate telemetry for selected time ranges.
3. Calculate metric statistics.
4. Return events/alerts for selected time ranges.
5. Return thresholds for each metric.
6. Analyze anomalies and related signals.

Recommended backend modules:

```text
telemetry/
  controller/
    TelemetryController
    TelemetryThresholdController
    TelemetryAnalysisController

  service/
    TelemetryQueryService
    TelemetryAggregationService
    TelemetryStatisticsService
    TelemetryThresholdService
    TelemetryEventService
    TelemetryAnalysisService
    DeviceHealthService

  model/
    TelemetrySample
    MetricDefinition
    MetricStatistics
    MetricThreshold
    TelemetryEvent
    DeviceHealthSnapshot
    AnalysisResult
```

### 3.3 Data flow

```text
Device / simulator / ingestion
        ↓
Telemetry storage
        ↓
Telemetry aggregation + statistics
        ↓
Threshold/anomaly detection
        ↓
Events + device health status
        ↓
API
        ↓
Frontend overview + metric detail view
```

---

## 4. Domain Model

Use this as the target domain contract. Adapt names to the existing project style.

### 4.1 MetricDefinition

```ts
export type MetricStatus = 'normal' | 'warning' | 'critical' | 'stale' | 'no_data';
export type MetricKind = 'gauge' | 'counter' | 'accumulator' | 'signal';

export interface MetricDefinition {
  id: string;
  key: string;
  name: string;
  unit: string;
  kind: MetricKind;
  description?: string;
  defaultVisible: boolean;
  order: number;
}
```

### 4.2 TelemetrySample

```ts
export interface TelemetrySample {
  timestamp: string;
  metricKey: string;
  value: number | null;
  quality?: 'good' | 'partial' | 'bad';
}
```

### 4.3 MetricStatistics

```ts
export interface MetricStatistics {
  latest: number | null;
  min: number | null;
  max: number | null;
  avg: number | null;
  median?: number | null;
  p95?: number | null;
  p99?: number | null;
  samplesCount: number;
  missingSamplesCount?: number;
  anomaliesCount: number;
  alertsCount: number;
}
```

### 4.4 Thresholds

```ts
export interface MetricThreshold {
  metricKey: string;
  normalMin?: number;
  normalMax?: number;
  warningMin?: number;
  warningMax?: number;
  criticalMin?: number;
  criticalMax?: number;
  spikeThreshold?: number;
  spikeDurationSec?: number;
}
```

### 4.5 Events

```ts
export type TelemetryEventType =
  | 'threshold_exceeded'
  | 'spike_detected'
  | 'returned_to_normal'
  | 'data_gap'
  | 'device_offline'
  | 'custom';

export type EventSeverity = 'info' | 'warning' | 'critical';

export interface TelemetryEvent {
  id: string;
  timestamp: string;
  endTimestamp?: string;
  type: TelemetryEventType;
  severity: EventSeverity;
  metricKey?: string;
  title: string;
  description?: string;
  value?: number;
  threshold?: number;
}
```

### 4.6 Device health

```ts
export interface DeviceHealth {
  status: MetricStatus;
  reason: string;
  lastSampleAt: string | null;
  dataQuality: 'good' | 'partial' | 'bad' | 'unknown';
  activeAlertsCount: number;
}
```

### 4.7 Overview response

```ts
export interface TelemetryOverviewResponse {
  deviceId: string;
  range: {
    from: string;
    to: string;
    preset: '15m' | '1h' | '6h' | '24h' | '7d' | 'custom';
  };
  health: DeviceHealth;
  metrics: Array<{
    definition: MetricDefinition;
    status: MetricStatus;
    statistics: MetricStatistics;
    threshold?: MetricThreshold;
    series: TelemetrySample[];
  }>;
  events: TelemetryEvent[];
}
```

### 4.8 Metric detail response

```ts
export interface MetricDetailResponse {
  deviceId: string;
  metric: MetricDefinition;
  status: MetricStatus;
  statistics: MetricStatistics;
  threshold?: MetricThreshold;
  series: TelemetrySample[];
  events: TelemetryEvent[];
  relatedSignals: Array<{
    metricKey: string;
    name: string;
    correlation: 'increased' | 'decreased' | 'stable' | 'unknown';
    note: string;
  }>;
  analysis?: {
    summary: string;
    possibleCause?: string;
    recommendedAction?: string;
  };
}
```

---

## 5. API Contract

If possible, implement these endpoints. If the project already has similar endpoints, adapt the frontend API layer to the existing endpoints.

### 5.1 Get telemetry overview

```http
GET /api/devices/{deviceId}/telemetry/overview?from={iso}&to={iso}&metrics=activePower,voltage,current,energy&aggregation=balanced
```

Returns `TelemetryOverviewResponse`.

### 5.2 Get one metric detail

```http
GET /api/devices/{deviceId}/telemetry/metrics/{metricKey}?from={iso}&to={iso}&aggregation=balanced
```

Returns `MetricDetailResponse`.

### 5.3 Get events

```http
GET /api/devices/{deviceId}/telemetry/events?from={iso}&to={iso}&metricKey=activePower
```

Returns `TelemetryEvent[]`.

### 5.4 Get/update thresholds

```http
GET /api/devices/{deviceId}/telemetry/thresholds
PUT /api/devices/{deviceId}/telemetry/thresholds/{metricKey}
```

### 5.5 Analyze event

```http
GET /api/devices/{deviceId}/telemetry/events/{eventId}/analysis
```

Returns:

```ts
interface EventAnalysisResponse {
  event: TelemetryEvent;
  relatedSignals: Array<{
    metricKey: string;
    before: number | null;
    during: number | null;
    after: number | null;
    change: 'increased' | 'decreased' | 'stable' | 'unknown';
  }>;
  summary: string;
  possibleCause?: string;
  recommendedAction?: string;
}
```

---

## 6. UI Implementation Plan

## Phase 1 — Refactor current screen structure

### Goal

Make the current screen cleaner without changing business logic too much.

### Tasks

1. Create `TelemetryOverviewPage`.
2. Move page title and device status into `TelemetryHeader`.
3. Move time range, aggregation, metric selector, and show-points toggle into `TelemetryToolbar`.
4. Replace metric checkboxes in the top-right area with a compact `MetricSelector` dropdown.
5. Create `MetricGrid` for chart cards.
6. Create reusable `MetricCard`.
7. Replace the `Open` button text with `Analyze` or `Details`.
8. Add visual status badge to each metric card.
9. Add simple summary values to each metric card: latest, avg, min, max, alerts.
10. Keep existing chart data source initially.

### Acceptance criteria

- The overview page is visually structured into header, toolbar, summary, chart grid, and timeline area.
- Metric controls no longer visually overload the top-right corner.
- Every metric card shows status and basic statistics.
- Problematic metrics are visually distinguishable from normal metrics.

---

## Phase 2 — Build proper chart component

### Goal

Create one reusable chart component that supports thresholds, events, tooltips, and detail/overview modes.

### Tasks

1. Create `TelemetryChart` component.
2. Support two sizes/modes:
   - `compact` for cards.
   - `detailed` for metric detail view.
3. Add threshold lines/zones.
4. Add event markers as vertical lines or points.
5. Add tooltip with timestamp, value, unit, status, and event if present.
6. Add optional `showPoints` support.
7. Add empty/loading/error states.
8. Format Y-axis values cleanly. Avoid strange values such as `64.7 W` if rounded values are more readable.
9. Format time axis based on selected range.

### Acceptance criteria

- Same chart component works both in cards and detail view.
- Thresholds are visible and readable.
- Event markers are visible and clickable or at least hoverable.
- Tooltip provides useful analytical context.

---

## Phase 3 — Replace current modal with detail analysis view

### Goal

Opening one graph should provide significantly more value than the small overview card.

### Tasks

1. Remove or redesign the current small modal.
2. Implement either `MetricDetailDrawer` or `MetricDetailPage`.
3. The detail view must contain:
   - title with metric name;
   - status badge;
   - sample count;
   - selected time range;
   - stats bar;
   - large chart;
   - threshold section;
   - event list;
   - related signals/correlation section;
   - analysis/recommendation section.
4. Make the chart the main element of the detail view.
5. Add close/back navigation.
6. Preserve current selected time range and aggregation when opening detail.

### Acceptance criteria

- Detail view uses most of the available space for analysis.
- The graph is at least 2–3 times more useful/readable than in the current modal.
- User can understand whether the metric is normal or problematic without manually reading the graph.

---

## Phase 4 — Add event timeline

### Goal

Connect charts with events and alerts.

### Tasks

1. Create `EventTimeline` component under the chart grid.
2. Show events in selected time range.
3. Each event item must show:
   - time;
   - severity;
   - metric;
   - title;
   - short description.
4. Clicking an event should:
   - highlight the event marker on charts;
   - optionally open event analysis.
5. If there are no events, show useful empty state:
   - `No events in selected range`.

### Acceptance criteria

- User can see what happened without inspecting every chart manually.
- Events are visually linked to chart markers.

---

## Phase 5 — Add basic analytics and health status

### Goal

Turn raw telemetry into monitoring signals.

### Tasks

1. Implement utility functions in `telemetry.analytics.ts`:
   - calculate latest;
   - calculate min;
   - calculate max;
   - calculate average;
   - calculate P95/P99 if needed;
   - count missing samples;
   - detect threshold violations;
   - detect spikes;
   - determine metric status;
   - determine device health status.
2. Use thresholds to classify metric status:
   - normal;
   - warning;
   - critical;
   - stale;
   - no data.
3. Device health should be the worst meaningful status across selected metrics, with a human-readable reason.
4. Show `StaleDataBanner` if latest sample is too old.
5. Show `No data` state if there are no telemetry samples.

### Acceptance criteria

- UI status is not hardcoded.
- Metric status is computed from data and thresholds.
- Device status explains the main reason.

---

## Phase 6 — Add correlation/event analysis

### Goal

Help the user understand why an anomaly happened.

### Tasks

1. When user selects a spike/anomaly event, compare related metrics before, during, and after the event.
2. For MVP, implement simple rules:
   - If active power increased and current increased, but voltage stayed stable: likely load increase.
   - If voltage dropped and current/power changed: possible power supply issue.
   - If signal strength dropped and telemetry gaps appeared: possible connectivity issue.
   - If only one metric changed: mark as isolated anomaly.
3. Display the result in `CorrelationPanel` and `RecommendationPanel`.

Example output:

```text
Power spike detected at 3:44 PM.
Active power increased to 138 W.
Current also increased.
Voltage stayed stable.

Likely reason: device load increased, not power supply instability.
Recommended action: check connected load or power-consuming component.
```

### Acceptance criteria

- User gets a plain-language explanation of the event.
- Related metric changes are visible.
- The system does not pretend to know more than it can infer.

---

## Phase 7 — Threshold configuration

### Goal

Make monitoring rules understandable and configurable.

### Tasks

1. Display threshold values in metric detail view.
2. Add `Configure alert` action.
3. If write support exists, implement threshold editing.
4. If write support does not exist, implement read-only threshold section first.
5. Explain rule logic:
   - normal range;
   - warning threshold;
   - critical threshold;
   - spike duration.

### Acceptance criteria

- User understands why an alert was triggered.
- Threshold source is no longer hidden.

---

## Phase 8 — Compare mode

### Goal

Allow serious analysis over time.

### Tasks

1. Add `Compare` control to toolbar.
2. Support comparison options:
   - Off;
   - Previous period;
   - Previous hour;
   - Yesterday;
   - Same time last week;
   - Another device, if available later.
3. Render comparison line in detail chart.
4. Show delta in stats bar.

### Acceptance criteria

- User can compare current behavior against a baseline.
- Comparison is optional and does not overload the default view.

---

## 7. Backend Implementation Plan

If backend work is included, implement in this order.

### Phase B1 — Query and aggregation service

1. Create `TelemetryQueryService`.
2. Input:
   - device ID;
   - time range;
   - selected metrics;
   - aggregation mode.
3. Return normalized time series.
4. Add aggregation modes:
   - raw;
   - balanced;
   - compact.
5. `balanced` should reduce points enough for UI performance while preserving spikes.

### Phase B2 — Statistics service

1. Create `TelemetryStatisticsService`.
2. Calculate:
   - latest;
   - min;
   - max;
   - avg;
   - median if needed;
   - P95/P99 if needed;
   - sample count;
   - missing samples;
   - anomalies count.

### Phase B3 — Threshold service

1. Create `TelemetryThresholdService`.
2. Store thresholds per:
   - metric type;
   - device type;
   - device instance override.
3. Resolve threshold priority:
   - device-specific threshold;
   - device-type threshold;
   - global metric default.

### Phase B4 — Event service

1. Create `TelemetryEventService`.
2. Generate or query events:
   - threshold exceeded;
   - spike detected;
   - returned to normal;
   - data gap;
   - device offline.
3. Store events if long-term history is needed.
4. For MVP, events can be computed on read if storage is not ready.

### Phase B5 — Health service

1. Create `DeviceHealthService`.
2. Determine device health based on:
   - latest sample age;
   - critical alerts;
   - warning alerts;
   - data quality;
   - selected key metrics.
3. Return health reason in plain language.

### Phase B6 — Analysis service

1. Create `TelemetryAnalysisService`.
2. For selected event, compare related metrics in windows:
   - before event;
   - during event;
   - after event.
3. Return simple rule-based explanation.
4. Do not use complex ML initially.

---

## 8. Suggested Database Model

Adapt to existing persistence. If telemetry is already stored elsewhere, do not duplicate storage unnecessarily.

### 8.1 `metric_definition`

```sql
id
key
name
unit
kind
order_index
default_visible
description
```

### 8.2 `telemetry_sample`

For large volume data, prefer a time-series optimized store if available. If not, use existing storage with proper indexes.

```sql
id
device_id
metric_key
timestamp
value
quality
```

Important indexes:

```sql
(device_id, metric_key, timestamp)
(device_id, timestamp)
```

### 8.3 `metric_threshold`

```sql
id
device_id nullable
device_type nullable
metric_key
normal_min
normal_max
warning_min
warning_max
critical_min
critical_max
spike_threshold
spike_duration_sec
```

### 8.4 `telemetry_event`

```sql
id
device_id
metric_key nullable
type
severity
timestamp
end_timestamp nullable
title
description
value nullable
threshold nullable
status
```

---

## 9. Frontend State Management

Recommended frontend state:

```ts
interface TelemetryPageState {
  deviceId: string;
  timeRange: TimeRange;
  aggregation: 'raw' | 'balanced' | 'compact';
  selectedMetricKeys: string[];
  showPoints: boolean;
  compareMode: 'off' | 'previous_period' | 'previous_hour' | 'yesterday' | 'last_week';
  selectedEventId?: string;
  openedMetricKey?: string;
}
```

Keep this state either:

- in URL query params, if users should be able to share links;
- or in local component state for MVP.

Recommended URL example:

```text
/devices/{deviceId}/telemetry?range=1h&metrics=activePower,voltage,current&aggregation=balanced
```

---

## 10. UX Rules

### 10.1 Default view must be calm

Do not make everything red or noisy. Use clear but restrained statuses:

- Normal: calm neutral/green indication.
- Warning: noticeable but not aggressive.
- Critical: strong visual warning.
- Stale: gray/yellow, focused on data freshness.

### 10.2 The user should not need to decode charts manually

Every important chart should have a small textual conclusion.

Examples:

```text
Voltage is stable.
```

```text
Power spike detected. Current increased at the same time.
```

```text
No data received for 18 minutes.
```

### 10.3 Controls should not dominate the page

Toolbar should be compact. Metric selection should be in dropdown/panel, not a large row of checkboxes.

### 10.4 Detail view should justify its existence

Do not open a detail modal that only shows the same graph bigger. Detail view must add:

- statistics;
- thresholds;
- events;
- analysis;
- recommendations.

---

## 11. MVP Scope

If time is limited, implement only this MVP:

1. Refactor overview layout.
2. Add metric cards with stats and status.
3. Replace current modal with large detail view.
4. Add thresholds and event markers to charts.
5. Add event timeline.
6. Add simple rule-based analysis for selected events.

Do not start with complex ML, advanced anomaly detection, or too many configuration screens.

---

## 12. Exact Task List for AI Agent

### Task 1 — Inspect existing telemetry implementation

- Find current telemetry page component.
- Find current chart component.
- Find current API/data source.
- Find existing device model and telemetry sample format.
- Do not rewrite everything immediately.
- Prepare a small refactor plan inside code comments or implementation notes.

### Task 2 — Add telemetry domain types

- Create `features/telemetry/model/telemetry.types.ts`.
- Add types from section 4.
- Create mappers from existing API response to these types.

### Task 3 — Create overview layout components

- Create `TelemetryHeader`.
- Create `TelemetryToolbar`.
- Create `HealthSummaryCards`.
- Create `MetricGrid`.
- Create `MetricCard`.
- Create `EventTimeline`.

### Task 4 — Move existing page into new layout

- Keep existing data source.
- Use mappers to fit new components.
- Preserve current time range behavior.
- Preserve metric selection behavior.

### Task 5 — Add statistics calculation

- Create `telemetry.analytics.ts`.
- Implement latest/min/max/avg/sample count/anomaly count.
- Use this in metric cards.

### Task 6 — Add metric status calculation

- Implement threshold-based status logic.
- Add temporary default thresholds if backend does not provide them.
- Mark cards as normal/warning/critical/stale/no data.

### Task 7 — Implement improved chart

- Create or refactor `TelemetryChart`.
- Add compact and detailed modes.
- Add threshold lines/zones.
- Add event markers.
- Add tooltip.
- Add loading/empty states.

### Task 8 — Replace single-chart modal

- Create `MetricDetailDrawer` or `MetricDetailPage`.
- Open it from `MetricCard`.
- Add stats bar, large chart, thresholds, events, and analysis panel.
- Remove the current small useless modal or stop using it.

### Task 9 — Add event timeline

- Show events under the grid.
- Link events to chart markers.
- Allow selecting an event.

### Task 10 — Add basic correlation analysis

- When an event is selected, analyze related signals.
- Show plain-language explanation.
- Use simple rules from Phase 6.

### Task 11 — Add backend endpoints if needed

- Implement overview endpoint.
- Implement metric detail endpoint.
- Implement events endpoint.
- Implement thresholds endpoint.
- Keep backward compatibility if existing frontend uses old endpoints.

### Task 12 — Add tests

Frontend tests:

- Metric stats are calculated correctly.
- Metric status is calculated correctly.
- Metric card renders normal/warning/critical states.
- Detail view opens with selected metric.
- Empty/no data/stale states render correctly.

Backend tests, if backend is changed:

- Aggregation preserves spikes.
- Threshold detection works.
- Health status chooses correct severity.
- Event analysis returns correct explanation for known scenarios.

---

## 13. Acceptance Criteria for Final Result

The feature is complete when:

1. User sees device health status immediately.
2. User sees which metric is problematic immediately.
3. Every chart card has useful statistics, not only `Latest`.
4. Problematic metrics are visually different from normal metrics.
5. Clicking a metric opens a real analysis view, not a small duplicate chart.
6. Detail view has a large chart, stats, thresholds, events, and recommendation.
7. Events are visible both on charts and in timeline.
8. Power spike can be explained using related metrics.
9. Stale/no-data states are handled gracefully.
10. The code is split into clear frontend modules and does not become one giant component.

---

## 14. Non-goals for First Iteration

Do not implement in the first iteration unless everything above is done:

- ML-based anomaly detection.
- Complex alert routing.
- Notification channels.
- Advanced dashboard builder.
- User-customizable chart layout.
- Multi-device comparison.
- Long-term reporting PDF export.

These can be added later.

---

## 15. Recommended Implementation Order

Use this exact order:

1. Add domain types and mappers.
2. Refactor overview layout.
3. Add metric cards with stats/status.
4. Improve chart component.
5. Replace modal with detail view.
6. Add event timeline.
7. Add thresholds.
8. Add selected-event analysis.
9. Add backend endpoints only where current data is insufficient.
10. Add tests and cleanup.

---

## 16. Final UX Target

After implementation, a user should be able to open the telemetry screen and understand this within 5 seconds:

```text
The device is in Warning state.
The reason is an active power spike.
Voltage stayed stable.
Current increased together with power.
The likely reason is increased load, not power supply instability.
Recommended action: check the connected load or power-consuming component.
```

That is the product goal.
