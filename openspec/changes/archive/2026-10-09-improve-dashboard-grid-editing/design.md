## Decisions
Use react-grid-layout v2 for mouse/touch dragging and resizing, with explicit width, saved 12/6/1-column layouts and manifest minimum sizes. Commit only completed gestures through the existing editor; keep inactive screens and breakpoints unchanged. Disable interaction during conflict/recovery/save. Viewer remains a static grid.

Use lucide SVG icons instead of text glyphs. Edit cards have a dedicated visible title/drag strip; visualization titles remain optional in View. Settings use named sections in the right drawer. Keyboard move/resize alternatives remain accessible in panel options.

ECharts is a visualization engine, shadcn/ui distributes UI components; neither supplies dashboard placement. This change addresses the grid and existing accessible configuration first without migrating unrelated chart evidence semantics or the whole CSS system.
## Verification
Exercise grid stop callbacks for movement, resize and screen/breakpoint isolation; retain manifest limits, undo and autosave tests. Verify the live editor and viewer in the browser, frontend typecheck/tests/build and OpenSpec validation. DOM-only browser tools cannot independently prove physical mouse dragging; report that limitation honestly if native input is unavailable.

## Verification evidence
The installed grid is exercised with mouse events in integration tests rather
than mocked grid callbacks. Live DOM/browser inspection confirms the grid,
resize handles and settings; physical mouse gestures remain unverified because
the session browser tools provide DOM interaction only. Dependency audit reports
no advisories against the newly added grid or icon packages; existing unrelated
frontend advisories are outside this change. No agent workflow changes required.
