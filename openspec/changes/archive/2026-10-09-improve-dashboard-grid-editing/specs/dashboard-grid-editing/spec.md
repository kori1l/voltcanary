## ADDED Requirements
### Requirement: Familiar editable grid gestures
Edit SHALL show a placement grid and allow cards to move using a dedicated drag header and resize using visible edge/corner handles. Completed gestures SHALL preserve manifest limits, collision prevention, undo, draft autosave and independent screen/breakpoint layouts. View SHALL remain read-only without edit chrome.
#### Scenario: Move into free grid space
- **WHEN** an editor releases a dragged card in available grid space
- **THEN** the snapped layout is saved as one undoable operation without requiring another card as an anchor
#### Scenario: Resize a chart
- **WHEN** an editor drags a chart resize handle
- **THEN** its dimensions follow the grid, remain within bounds and minimum constraints and leave other screen/breakpoint layouts intact
### Requirement: Discoverable panel configuration
Edit SHALL show clear SVG controls and a visible panel title/drag strip, organize settings into named sections and retain keyboard movement/resizing in panel options. Healthy View SHALL retain minimal visualization defaults.
#### Scenario: Configure an existing card
- **WHEN** the editor opens a card's Edit control
- **THEN** a right drawer presents discoverable Data, Calculation, Appearance and Navigation sections without exposing movement arrows as the main interaction
