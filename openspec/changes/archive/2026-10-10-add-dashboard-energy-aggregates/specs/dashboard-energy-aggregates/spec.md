## ADDED Requirements

### Requirement: Counter consumption preserves meter identity and resets

Counter energy SHALL use cumulative differences within one canonical meter/source segment and explicitly handle resets, replacement and boundary coverage; negative changes SHALL NOT become invented consumption.

#### Scenario: Complete counter delta

- **GIVEN** one meter changes from10000Wh to10500Wh across a fully covered interval
- **WHEN** energy is calculated
- **THEN** the result is0.5kWh with COUNTER_DELTA provenance, not the sum of readings.

#### Scenario: Reset or missing boundary

- **GIVEN** a counter drops or a boundary sample is absent
- **WHEN** aggregation occurs
- **THEN** the result records the segment/reset and partial or unknown coverage instead of a fabricated positive consumption.

### Requirement: Power integration and meter scope are explicit

Power-derived energy SHALL use elapsed-time trapezoidal integration over full qualified data, exclude excessive gaps, remain marked estimate, and reject overlapping or duplicate physical meter aggregates.

#### Scenario: Constant power and gap

- **GIVEN** power is1000W for two covered hours
- **WHEN** integration or a large-gap variant is calculated
- **THEN** complete coverage returns2kWh; the gap variant records missing coverage and never carries power through the gap as measured consumption.

#### Scenario: Parent meter and submeter selected

- **GIVEN** a parent physical meter and its child are in scope
- **WHEN** an aggregate is requested
- **THEN** the overlapping selection is rejected or resolved by an explicit reviewed non-overlap boundary; values are not double counted.

### Requirement: Energy comparisons retain authorized coverage and method

Energy totals and rankings SHALL expose full authorized evaluated scope, coverage, units and selected authoritative method, include raw maintenance consumption and keep undefined comparison percentages unavailable.

#### Scenario: Two methods and zero comparator

- **GIVEN** counter and power-derived values exist with a zero comparison baseline
- **WHEN** the energy widget is rendered
- **THEN** one saved authoritative method is used, absolute delta is available in panel information and shown inline only when comparison is enabled, and percent is undefined; both methods are not added together.

#### Scenario: Unavailable device during maintenance

- **GIVEN** one scoped meter lacks evidence and another consumed energy during maintenance
- **WHEN** the raw energy period is queried
- **THEN** raw measured consumption remains included and totals are labeled partial with only authorized coverage metadata.
