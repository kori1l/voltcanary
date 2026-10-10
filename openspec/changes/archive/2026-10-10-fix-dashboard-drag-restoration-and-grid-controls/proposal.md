## Why
The free-placement grid retains neighbour displacements accumulated along a drag path. Users also cannot discover grid settings because responsive layout controls are buried in dashboard metadata.
## What Changes
- Recompute collision displacement from the gesture's original layout so neighbours return when the dragged/resized panel stops overlapping them.
- Add a named Grid entry with responsive layout selection, guide visibility, numeric column/row-height/spacing dimensions, exact panel width/height and an explicit undoable compact command.
- Allow panels smaller than their insertion defaults and make charts fill their available height and width.
## Capabilities
### New Capabilities
- `dashboard-grid-restoration`: Reversible neighbour previews and discoverable grid controls.
### Modified Capabilities
## Impact
Frontend grid adapter, editor/model controls and dashboard-service screen JSON validation. Optional per-screen/per-breakpoint grid settings preserve legacy defaults; no database migration or ownership changes.
