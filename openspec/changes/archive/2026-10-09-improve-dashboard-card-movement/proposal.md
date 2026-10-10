## Why
Users cannot discover how to move dashboard cards. Existing controls are hidden in Arrange, and the canvas has no direct manipulation.

## What Changes
- Add visible movement handles and directional controls to editor cards.
- Preview relative destination on the canvas before committing pointer movement.
- Preserve undo, independent responsive layouts, screen isolation and private draft persistence.
- Explain where a dashboard is saved and which screen is being edited.

## Capabilities
### New Capabilities
- `dashboard-card-movement`: direct and accessible movement of existing cards.
### Modified Capabilities

## Impact
Frontend editor only; existing layout and draft APIs remain authoritative. Depends on implemented dashboard working screens.
