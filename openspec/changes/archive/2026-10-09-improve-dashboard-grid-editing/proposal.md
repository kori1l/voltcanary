## Why
The hand-written card-edge drag interaction does not offer familiar free grid placement or pointer resizing. Hidden symbolic controls and an invisible grid make widget editing difficult to discover.
## What Changes
- Adopt react-grid-layout for editable dashboard placement and resize handles, preserving saved responsive layouts and undo/autosave.
- Show a grid in Edit, a dedicated draggable card header, clear SVG controls and keyboard placement in a secondary menu.
- Simplify widget settings navigation while preserving authorized data configuration and minimal viewer presentation.
## Capabilities
### New Capabilities
- `dashboard-grid-editing`: Familiar pointer drag/resize editing and discoverable settings.
### Modified Capabilities
## Impact
Frontend dashboard canvas/editor/configuration, frontend dependencies, dashboard workflow docs. Existing dashboard JSON and owner query contracts remain compatible.
