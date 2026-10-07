## MODIFIED Requirements

### Requirement: Scannable fleet inventory

Devices SHALL default to recognizable appliance icon tiles grouped by location and provide an optional compact fleet table, with name, location, registry state, connection freshness and risk; search, filters and first-use states SHALL remain readable across widths.

#### Scenario: An operator opens the fleet

- **WHEN** Devices is opened
- **THEN** location-grouped tiles identify appliance types through icons and names, and the operator can switch to a table without losing filters.

#### Scenario: An operator scans a large fleet

- **WHEN** the table view or filters are selected
- **THEN** devices can be compared without opening each card and inactive registration is not confused with missing telemetry.
