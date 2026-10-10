## Decisions
Show a nonmodal, closable right palette so the canvas remains reachable during HTML drag/drop. List manifest-supported types with familiar SVG previews, concise task labels and search; unsupported types show their dependency without draggable/add actions. Use react-grid-layout external droppingItem and onDrop for placement. Treat drag data as untrusted: accept only an existing supported manifest and respect edit locks and the whole-definition 24-widget limit.

On drop, preserve collision-adjusted positions from the grid, map the temporary dropping ID to the new widget ID and update only the active screen/section and selected breakpoint. Normalize missing layouts for other breakpoints without rewriting existing ones. Cancelled drags do not insert cards. Configure inserted widgets using the existing right drawer; saved evidence is never fabricated by palette previews. Click-to-add uses the next free position and is the keyboard/mobile alternative. Existing relative add/move remains secondary.
## Verification
Test search, capability/limit locks, drag payload and click insertion; exercise external drop through the installed grid and check screen/breakpoint preservation, settings opening and undo. Inspect desktop/mobile live UI and screenshots. Run frontend checks and strict OpenSpec validation; document the DOM-only browser limitation on physical drag testing.

## Exercised verification
Native browser mouse dragging was available in this turn: dragging Current value
from the palette onto free grid space inserted it and opened configuration.
Click addition, unbound initial state, Undo and the nonmodal palette were also
inspected. Test insertions were undone, preserving prior user screen/layout edits.
The grid integration tests exercise HTML drag/drop against the installed library
without mocking its drop callbacks. No AGENTS.md or skill workflow changes needed.
