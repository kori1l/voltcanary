# dashboard-grid-restoration Specification

## Purpose
Define reversible gesture previews, numeric per-screen grid profiles and flexible panels with charts filling the available area.
## Requirements
### Requirement: Neighbour displacement reflects current gesture position
Grid dragging and resizing SHALL resolve neighbours from the gesture's initial layout on every frame. A neighbour SHALL return to its original position when it no longer collides; traversing other panels SHALL NOT accumulate permanent displacement. Only the final valid layout SHALL be saved as one undoable operation.
#### Scenario: Cross a neighbour then move away
- **WHEN** an editor drags a panel over a neighbour then into free space before releasing
- **THEN** the neighbour returns to its initial position and the saved layout contains no displacement left by the drag path
#### Scenario: Return to the starting position
- **WHEN** the editor returns the dragged panel to its original position and releases
- **THEN** all original positions remain unchanged without creating a layout edit

### Requirement: Grid controls are discoverable
Edit SHALL expose a named Grid control for selecting the responsive layout and guide visibility, accept numeric columns, row height and spacing with validated bounds, and offer explicit undoable compaction of the active layout. Other screens and breakpoints SHALL remain unchanged. Saved optional screen grid profiles SHALL survive draft reload, copy and publication; legacy definitions SHALL retain default rendering.
#### Scenario: Configure the working grid
- **WHEN** the editor opens Grid
- **THEN** the right drawer accepts 1–48 columns, 16–160px row height and 0–32px spacing, saves them per screen/profile and explains numeric panel width/height

#### Scenario: Invalid dimensions or changed column count
- **WHEN** an editor submits invalid/noninteger dimensions or reduces the column count
- **THEN** invalid values are rejected and valid changes clamp/reflow only that screen/profile without overlaps or inaccessible widgets

### Requirement: Flexible panel dimensions and chart area
Panels SHALL allow sizes down to one column and one row without changing default insertion sizes. Charts SHALL use the available panel width and height in both Edit and View, with responsive coordinates rather than fixed aspect-ratio letterboxing. Saved small dimensions SHALL survive normalization and publication.
#### Scenario: Dense screen with a tall chart
- **WHEN** an editor reduces panel dimensions or stretches a chart vertically
- **THEN** compact panels retain their chosen dimensions and the chart plot expands into the available height with legible unscaled labels

