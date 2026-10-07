## ADDED Requirements

### Requirement: Terminal dependent resources and scoped retained evidence

Console resources SHALL distinguish waiting for a dependency from an active request, terminate waiting when the dependency fails, and retain previous evidence only under the same authorized scope with a local stale timestamp.

#### Scenario: Inventory dependency fails

- **GIVEN** signals depend on inventory and no signals request has started
- **WHEN** inventory fails terminally
- **THEN** signals show unavailable dependency and an actionable retry rather than permanent Loading.

#### Scenario: Filter changes before failure

- **GIVEN** scope A contains evidence
- **WHEN** scope B fails or an obsolete A response arrives
- **THEN** B SHALL NOT display A evidence as its result; loss of authorization clears retained values.

### Requirement: Entity-specific freshness and read polling

Telemetry and risk resources SHALL expose independent status and timestamps per device; age labels SHALL update by clock, and optional polling SHALL pause hidden views and never execute mutations.

#### Scenario: One risk request fails

- **GIVEN** two devices have current telemetry and previous risk
- **WHEN** one risk refresh fails
- **THEN** only that risk cell is stale, other evidence remains current, and a clock advance updates age without user interaction.

#### Scenario: Hidden view resumes

- **GIVEN** read polling is configured
- **WHEN** the browser becomes hidden and later resumes
- **THEN** polling pauses, then checks authorization and age before a bounded read refresh; no recalculation or token rotation occurs.
