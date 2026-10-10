## Decisions
Keep the installed grid engine but provide a compactor that resolves each gesture frame against its initial layout. Pin the active panel and restore non-overlapping neighbours to their original positions; cascade only present collisions. Commit only the final layout, with existing undo/autosave and cancellation. Apply the same policy to resizing.

Grid in the header opens the right drawer. Select Computer/Tablet/Phone and enter columns (1–48), row height (16–160px) and spacing (0–32px). Optional Screen.grid stores complete profiles per breakpoint; defaults are 12/6/1 columns, 48px rows and 12px spacing. Server validates integer ranges and placements against that screen’s configured columns. Changing columns clamps/reflows only that screen/profile; other screens and profiles stay unchanged. Viewer, copies, undo and published revisions retain the saved grid. Panel settings also allow numeric width/height. Explicit Compact layout uses the library vertical compactor on the active responsive surface and is undoable; it does not implicitly rewrite manually placed layouts on load. Grid guides are an editor preference rather than published data.
## Verification
Exercise real mouse traversal over a neighbour then away/back, final overlap and resize restoration against the installed library. Cover current-screen/breakpoint isolation and compact action. Inspect live grid controls; run frontend checks, strict OpenSpec and structure. No SQL migration or agent workflow changes. Backend unit/integration and live draft-save/reload checks cover the additive Screen.grid contract.

## Runtime verification
Backend dashboard-service Maven tests include PostgreSQL integration and custom
profile validation. A local isolated verification dashboard was created with
24 columns/32px rows/6px gap, published and copied; both readbacks retained
Screen.grid. Both temporary dashboards were archived afterwards. Docker daemon
and other services were left running; only dashboard-service was restarted to
load the additive contract. Browser settings show numeric inputs with range
validation. Gesture tests exercise the installed library with mouse events
across neighbours, return-to-start and grow/shrink resize restoration.

Panel minima are one column and one row, using edge/corner resize or exact
Width/Height fields in Layout settings. Separate insertion defaults preserve
normal 6x5 charts and 3x3 summary sizes. View uses the same fixed grid row height
as Edit; excess content scrolls locally. Dashboard plots measure their available
SVG area and recompute geometry without stretching labels or letterboxing tall
cards. Other telemetry pages retain default sizing. Very short cards reveal the
edit toolbar on hover or keyboard focus to leave space for their content.

Final validation: frontend 79 files/237 tests, typecheck and production build;
dashboard-service Maven reactor tests (including PostgreSQL integration); strict
OpenSpec and structure checks passed. Browser View measured 254px SVG height in
280px panels and 410px SVG height in a 436px panel, with matching responsive
viewBoxes. Exact 3x2 resizing produced a 46px-high card on the user's custom
21-column screen; Undo restored its previous 6x11 size and the draft was saved.
Published user revision 6 was not changed. Screenshot:
artifacts/dashboard-flexible-chart-height.jpg. No agent workflow update needed.
