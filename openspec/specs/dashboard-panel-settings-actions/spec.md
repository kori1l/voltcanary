# dashboard-panel-settings-actions Specification

## Purpose
Keep panel arrangement and management controls in the right settings drawer without floating forms over the canvas.
## Requirements
### Requirement: Panel actions belong in settings
Edit SHALL open a right panel settings drawer containing layout, screen/section, duplicate and delete controls; card ellipsis menus SHALL NOT expose floating settings forms. Pointer drag/resize SHALL remain available on the canvas, and keyboard placement/size commands SHALL remain available in settings.
#### Scenario: Open panel layout settings
- **WHEN** an editor opens Edit and selects Layout
- **THEN** arrangement and management controls appear inside the right drawer without covering another panel with a floating menu
#### Scenario: Manage a panel safely
- **WHEN** an editor moves, resizes, transfers, duplicates or removes a panel from settings
- **THEN** existing locks, widget limits, independent screen/breakpoint placement, undo and draft autosave apply while the published revision remains unchanged

