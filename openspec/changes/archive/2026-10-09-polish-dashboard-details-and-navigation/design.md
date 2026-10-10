## Decisions
Remove card Add and anchor state; the main header palette remains the sole widget catalog entry. Evidence details use label/value rows, wrapped identifiers and expandable record lists with explicit paging instead of arbitrarily wide nested tables. Denied evidence stays purged and information opens without extra reads.

Desktop has one header control with PanelLeftOpen/PanelLeftClose SVG states and a hideable sidebar. Mobile uses the same header entry to open a native left navigation dialog, reusing focus containment, Escape and focus restoration; there is no second hamburger/menu control in the sidebar. Close buttons use X SVG with an accessible Close name. Kiosk remains without navigation.
## Verification
Test evidence nesting/paging and denied values, navigation uniqueness and mobile dialog behavior, existing dialog semantics and dashboard workflows. Inspect live information and mobile/desktop navigation. Run frontend typecheck/tests/build, OpenSpec and structure checks; no agent workflow changes required.

## Verification evidence
50 focused tests pass for dashboard information, nested records, denied evidence,
navigation and dialogs. Typecheck and build pass. Live browser inspection confirms
no tile Add controls, one sidebar toggle, structured information and the mobile
left drawer. The broad frontend run passed 223 tests and had one intermittent ML
snapshot-training UI timing failure; that exact scenario passes independently.
Broad-run failure is recorded rather than claiming all tests passed together.
No AGENTS.md or skill workflow updates are required.
