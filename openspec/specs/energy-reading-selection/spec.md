# energy-reading-selection Specification

## Purpose
TBD - created by archiving change narrow-dashboard-energy-reading-selection. Update Purpose after archive.
## Requirements
### Requirement: Narrow bounded raw energy reads
Energy owner reads SHALL select authorized devices, canonical source IDs, required
metrics and physical/time boundaries in SQL using one measurement SELECT per batch.
Overlapping selections SHALL NOT duplicate or collapse original sample rows. Reads
SHALL preserve quality barriers and the existing selected-sample budget.

#### Scenario: Unrelated source history
- **WHEN** an authorized device has large noncanonical source history
- **THEN** those samples are not delivered to its energy calculator

### Requirement: Indexed interval selection preserves evidence
Each request SHALL group and normalize returned samples once per canonical stream
and use indexed time ranges and prefix diagnostic totals rather than scan the
complete sample union per interval or epoch. Calculation results
SHALL preserve existing counters, gaps, resets/rollovers, replacement boundaries,
same-meter revision continuity, comparisons and raw maintenance consumption.

#### Scenario: Overlapping cycles and meter replacement
- **WHEN** cycles overlap and physical meter identity changes
- **THEN** each cycle matches its original calculation without cross-meter bracketing

