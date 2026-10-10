# dashboard-widget-palette Specification

## Purpose
Define a visual widget catalog with direct grid placement and equivalent keyboard/mobile addition.
## Requirements
### Requirement: Visual widget palette
The Edit header SHALL offer the sole catalog entry for a searchable nonmodal widget palette with visual type previews and labels. Supported cards SHALL be draggable and addable by button; unsupported capabilities and definition limits SHALL remain explicit. View SHALL NOT expose the palette. Previews SHALL NOT imply measured evidence.
#### Scenario: Choose a widget visually
- **WHEN** an editor opens Add widget and searches for a chart or task
- **THEN** matching manifest types appear with visual previews while the working canvas remains reachable
#### Scenario: Keyboard or mobile addition
- **WHEN** the editor activates Add on a supported palette card
- **THEN** a panel is inserted at a free position on the active screen and its configuration opens

### Requirement: Snapped external widget placement
The canvas SHALL preview a supported dragged widget's snapped size and insert it at the released location. Drop SHALL respect edit locks, manifest sizes, collision policy and widget limits, preserve other screens/breakpoints and support undo/autosave. Cancelled or invalid drops SHALL NOT edit the definition.
#### Scenario: Drop onto the active grid
- **WHEN** an editor drops a supported palette card onto a grid position
- **THEN** one new panel is inserted at the previewed position with collision-adjusted neighbours, and its settings open without mutating the published revision
#### Scenario: Cancel or unsupported drop
- **WHEN** the drag is cancelled or carries an unknown/unsupported type
- **THEN** no widget is inserted and the existing layout remains unchanged

