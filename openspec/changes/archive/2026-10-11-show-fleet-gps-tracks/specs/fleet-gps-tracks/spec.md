## ADDED Requirements

### Requirement: Recorded tracks on a common fleet map
The common vehicle map SHALL show recorded GPS trails for all authorized vehicles by default, using an interval of at most fifteen minutes ending at the dashboard evidence timestamp and at most 1000 points per vehicle. Every device and source SHALL be validated before track reads. Old single-vehicle evidence SHALL remain compatible.

#### Scenario: Selecting a moving tram
- **WHEN** the user opens the Helsinki map and selects a tram
- **THEN** recorded trails are visible and the selected tram's trail is highlighted
- **AND** a Show tracks control can hide all trails

#### Scenario: Missing or discontinuous history
- **WHEN** recorded positions have gaps or impossible jumps
- **THEN** those discontinuities are connected by faint straight lines explicitly distinguished from recorded paths
- **AND** missing history or truncation is stated without inventing coordinates
