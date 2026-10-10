# dashboard-card-movement Specification

## Purpose
Define discoverable card movement with equivalent pointer and keyboard placement, cancellation and undo.
## Requirements
### Requirement: Discoverable movement of screen cards
The editor SHALL expose a visible draggable card header and resize handles in Edit, with equivalent keyboard movement and size controls in panel settings. Movement SHALL change only the active screen's selected responsive layout and retain undo and draft persistence; edit controls SHALL occupy a compact header and View SHALL retain minimal visualization defaults.

#### Scenario: Drag to a valid destination
- **WHEN** the user drags a card header to a valid grid position
- **THEN** the editor previews the snapped grid position and commits placement only on release inside the canvas

#### Scenario: Cancel or impossible placement
- **WHEN** a drag is cancelled with Escape or released outside the canvas
- **THEN** the draft remains unchanged and the editor announces cancellation

#### Scenario: Move using keyboard buttons
- **WHEN** the user activates a movement button in the Layout section of panel settings
- **THEN** the card moves using the same collision policy, remains focused and can be restored with Undo

### Requirement: Clear editing destination
The editor SHALL identify the active screen and saved dashboard; responsive layout controls SHALL be available in Grid settings. The Dashboards parent link SHALL open /dashboards, and All dashboards SHALL remain available in dashboard settings. Publication and personal start-page selection SHALL remain explicit actions without repeated explanatory text above the canvas.

#### Scenario: Editing a saved dashboard
- **WHEN** the user opens the editor
- **THEN** they can identify where the draft is saved and which screen and layout movement affects
