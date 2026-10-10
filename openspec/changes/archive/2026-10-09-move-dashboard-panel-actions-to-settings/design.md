## Decisions
Add a Layout section to the right panel settings drawer and extract its controls into a focused component. Layout commands use the existing editor draft/undo/autosave and respect locks, responsive bounds and the 24-widget limit. Remove card ellipsis entirely; Edit remains the settings entry. Unsupported panels retain inert data but can still be moved, copied or removed from settings. Delete closes the drawer; moving between screens preserves widget identity. Data settings continue to require Apply changes, while layout actions update the draft immediately.
## Verification
Test the Layout section controls and workflow entry without card ellipsis, including movement limits and deletion, and run frontend typecheck/tests/build. Inspect the live right drawer and screenshot. Validate OpenSpec and structure; no agent workflow changes required.

## Verification evidence
43 dashboard tests pass, including layout limits, management and preserving pending
data edits across settings sections. Typecheck and build pass. Browser inspection
confirms zero card ellipsis menus and the Layout controls inside the right drawer.
The broad frontend run reported one intermittent DevicesPage restoration test
failure; the unchanged device suite passes independently (2 tests). This result
is recorded rather than claiming a clean broad run. No repository workflow or
AGENTS.md/skill updates are needed.
