## Why
The user wants varied diagrams, including pie charts, instead of dashboards dominated by numeric cards and one line-chart style. The data query and its visual representation must remain separate so users can choose a suitable chart without creating duplicate business widget types.
## What Changes
- Add compatible presentation choices: number, line, area, vertical/horizontal bars, stacked bars, pie and donut.
- Expose visual previews in the existing widget palette and a visualization picker in the right Presentation settings.
- Preserve data binding, calculation, time, screen and layout when switching presentation; save the choice through draft, copy and publication.
- Offer part-to-whole charts only for valid nonnegative, compatible, disjoint categorical data; retain explicit unavailable/partial states.
- Preserve the clean sidebar, compact canvas and optional annotations. No extra chart toolbar or navigation workflow.
## Capabilities
### New Capabilities
- `dashboard-visualization-types`: compatible rendering choices for typed widget evidence.
## Dependencies And Delivery
The current dashboard editor, palette, query engine and presentation contracts are prerequisites. Existing authorized count/series evidence can support the first delivery independently of the pending energy or team changes. Future energy, cost, operational and team widgets inherit these presentation choices when their typed evidence becomes available.
## Impact
Frontend dashboard palette/configuration/renderers, server manifest and definition validation if required, current/target dashboard documentation. No new energy/cost/cycle facts or permission grants are supplied by chart selection.
## Out Of Scope
Maps, gauges, arbitrary user scripts/SQL, new business calculations, fabricated measurements and implementation of other active changes.
