# compact-fleet-map Specification

## Purpose
TBD - created by archiving change compact-fleet-map-controls. Update Purpose after archive.
## Requirements
### Requirement: Compact fleet map presentation
The fleet view SHALL show vehicles in a bounded selectable table with name, freshness, speed and observation time. Map options SHALL reside in an initially collapsed top-right settings panel with accessible toggle, Escape and outside-click dismissal.

#### Scenario: Vehicle selection and map settings
- **WHEN** the user selects a vehicle in the table or on the map
- **THEN** the corresponding table row and map marker are selected
- **AND** existing fit-all, tracks and follow options remain available through the settings icon

The vehicle table is initially collapsed behind a compact Vehicles/count toggle. Opening and closing preserves selected vehicle and live map behavior.

