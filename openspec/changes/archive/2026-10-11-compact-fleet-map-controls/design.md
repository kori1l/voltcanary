# Design

The vehicle table shows name, freshness, speed (one decimal) and observation time, with bounded vertical scrolling and a selected row. The name remains a keyboard-accessible button styled as table text. A 32px settings icon opens an overlay at the map's top right containing fit-all, show-tracks, follow-selected and the track explanation. It is initially collapsed and closes on Escape or outside click. Navigation moves to bottom right to avoid covering the settings button. Map sizing and retained sources remain stable; controls do not add requests.

Verify row/marker selection, panel open/close and existing toggles, typecheck/build, focused UI tests and live browser. Update the existing HSL runbook.

The vehicle table is initially collapsed behind a compact Vehicles/count toggle. Opening and closing preserves selected vehicle and live map behavior.
