## ADDED Requirements

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
