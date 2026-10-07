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

