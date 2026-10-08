# telemetry-console-experience Specification

## Purpose
Define actionable device summaries, exact metric/range controls, evidence-preserving analysis and observable asynchronous recalculation.
## Requirements
### Requirement: Actionable device overview

Overview SHALL prioritize current state, evidence, freshness and next action, reduce duplicated full-monitoring blocks and repeated commands, and render unknown health/risk neutrally.

#### Scenario: A device has insufficient evidence

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** Overview is opened
- **THEN** the operator sees what is unknown and how to obtain evidence without a healthy badge or duplicated monitoring workspace.

### Requirement: Consistent metric and range controls

Telemetry SHALL reconcile selected versus available metric counts, provide named metric search/selection and exact timezone-aware date fields, offer a range containing actual samples and explain missing charts.

#### Scenario: Selected keys are unavailable in the current response

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** metrics or a Custom range are displayed
- **THEN** the count and chart list agree and missing data is explained rather than reported as an unexplained missing selection.

### Requirement: Evidence-preserving charts and analysis

Charts SHALL display units, missing-data gaps, freshness and shared event/range context. Analyze SHALL provide accessible alternatives to brush input and distinguish measurements, inferred risk, confidence and recommended checks.

#### Scenario: An operator opens metric details from a selected event

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** a range or signal is changed
- **THEN** details and related evidence share the intended interval, missing samples remain gaps, and estimates are not presented as exact diagnosis.

### Requirement: Focused analytics and alert actions

Analytics SHALL follow observation, risk/confidence and recommendation with technical lineage secondary. Recalculation SHALL expose job status and avoid duplicate primary commands; device Alerts SHALL share status/filter semantics and evidence links with global Inbox.

#### Scenario: An operator follows an alert to device analytics

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** recalculation or telemetry review is requested
- **THEN** the device, metric and interval context are retained and each asynchronous action has a visible progress/result state.

### Requirement: Historical metric discovery is independent from latest

Historical telemetry SHALL discover available metrics for the selected interval independently from the latest reading, and failure of latest SHALL NOT hide readable history.

#### Scenario: Metric disappeared from latest

- **GIVEN** temperature existed during the selected historical interval but not in the newest reading
- **WHEN** historical telemetry is opened
- **THEN** temperature metadata and authorized history remain selectable without fabricated current temperature.

### Requirement: Significant extrema and thresholds survive display sampling

Display sampling SHALL preserve bucket extrema, missing-data gaps and threshold evidence or explicitly use an envelope when a display budget is exceeded; business reducers SHALL NOT use sampled display points.

#### Scenario: Short trough and multiple peaks

- **GIVEN** dense input contains a critical trough and two distinct spikes
- **WHEN** display reduction is applied
- **THEN** the significant events remain visible, raw/display counts are shown, and measured zeros remain real data.

#### Scenario: More crossings than display capacity

- **GIVEN** threshold crossings exceed the display budget
- **WHEN** the chart is rendered
- **THEN** an explicit aggregation/envelope mode represents evidence instead of silently discarding threshold events.

### Requirement: Readable graph values without pointer hover

Telemetry details SHALL provide textual interval and extrema summaries plus accessible paginated values and event controls without requiring pointer hover.

#### Scenario: Keyboard-only evidence review

- **GIVEN** a chart has measurements and event markers
- **WHEN** the user navigates with keyboard or screen reader
- **THEN** units, values, event timestamps and range controls can be read and operated; export respects authorization and range caps.

