## ADDED Requirements
### Requirement: Discoverable movement of screen cards
The editor SHALL expose pointer movement handles and visible directional buttons for cards. Movement SHALL change only the active screen's selected responsive layout and retain undo and draft persistence.

#### Scenario: Drag to a valid destination
- **WHEN** the user drags a card handle toward an edge of another card
- **THEN** the editor previews and names the destination and commits relative placement only on release over that valid destination

#### Scenario: Cancel or impossible placement
- **WHEN** a drag is cancelled, released outside the canvas or cannot fit beside the target
- **THEN** the draft remains unchanged and the editor explains an impossible placement

#### Scenario: Move using keyboard buttons
- **WHEN** the user activates a visible movement button
- **THEN** the card moves using the same collision policy, remains focused and can be restored with Undo

### Requirement: Clear editing destination
The editor SHALL show the active screen, responsive layout and the My dashboards destination, and explain that publication and personal start-page selection are explicit actions.

#### Scenario: Editing a saved dashboard
- **WHEN** the user opens the editor
- **THEN** they can identify where the draft is saved and which screen and layout movement affects
