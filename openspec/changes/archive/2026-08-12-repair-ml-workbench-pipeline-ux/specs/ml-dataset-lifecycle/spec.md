## ADDED Requirements

### Requirement: Preview timeline carries operator-visible event markers

Analytics-service SHALL include maintenance and failure outcome evidence in
internal telemetry dataset preview timeline buckets.

#### Scenario: Preview range overlaps maintenance or failure outcomes

- **GIVEN** selected devices have maintenance windows or maintenance outcomes
  inside the preview range
- **WHEN** analytics-service returns a dataset workbench preview
- **THEN** matching timeline buckets SHALL include event labels for maintenance
  overlaps, ML exclusions, confirmed failures, corrective maintenance,
  unresolved anomalies, sensor faults, or unknown outcomes
- **AND** the response schema SHALL remain backward-compatible for clients that
  already treat `timeline[].events` as a string array.

#### Scenario: Preview range has no events

- **GIVEN** selected devices have telemetry but no maintenance or failure
  evidence inside the preview range
- **WHEN** analytics-service returns a dataset workbench preview
- **THEN** timeline buckets SHALL still be returned with an empty `events`
  array.
