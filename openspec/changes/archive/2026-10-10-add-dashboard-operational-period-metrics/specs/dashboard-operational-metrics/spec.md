## ADDED Requirements

### Requirement: Versioned operating windows and confirmed facts

Operational schedules SHALL declare effective timezone-aware windows and holidays, and cycle or downtime facts SHALL have authorized canonical source identity, completeness and audited corrections with idempotent import.

#### Scenario: Repeated cycle fact and correction

- **GIVEN** a source submits a completed cycle twice then corrects its end
- **WHEN** the facts are processed
- **THEN** one canonical cycle remains, the correction has a reason and audit, and no duplicate job is counted.

#### Scenario: DST or holiday

- **GIVEN** an operating schedule crosses DST or a declared holiday
- **WHEN** working intervals are resolved
- **THEN** calendar semantics are explicit and all comparisons use the same resolved operating windows.

### Requirement: Qualified cycle energy and downtime denominators

Cycle energy and uptime SHALL depend on confirmed complete facts and qualified energy/window coverage; missing readings, power spikes or runtimeHours changes SHALL NOT independently establish completed orders, cycles or downtime.

#### Scenario: Power spike without cycle facts

- **GIVEN** telemetry contains a large spike and gap
- **WHEN** W15 is queried
- **THEN** cycle count and downtime remain insufficient evidence instead of inferred completed work or confirmed outage.

#### Scenario: Overlapping downtime and partial energy

- **GIVEN** two confirmed downtime intervals overlap and a completed cycle lacks a boundary energy sample
- **WHEN** period metrics are calculated
- **THEN** downtime is unioned once, energy-per-cycle retains a visible compact partial/unavailable state, and authorized denominators and exclusions are available in panel information and optionally inline calculation annotations.
