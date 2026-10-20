## Data and presentation
A widget type defines an authorized query and evidence contract. A presentation visualization selects a renderer; pie/donut are not independent data-source types. Compatibility is declared by the manifest and verified against actual result shape, unit, reduction/window and category semantics. Do not offer every renderer for every widget. A scalar latest value cannot become a meaningful pie; min/mean/max summaries are overlapping statistics, not slices.
Use existing authorized categorical counts for initial pie/donut scenarios, compatible time series for line/area, and categorical measures for bars. If a new categorical projection is necessary, it must be typed, server-authorized and bounded; never aggregate just the currently displayed page as a full total. A renderer selection must not silently alter reducers, units or query scope.
## Interaction
Use the current palette and right Presentation drawer. Show labeled graphical previews for compatible renderers. Drag/add preserves current grid behavior. Switching visualization is one undoable draft change and retains binding, screen, layout, time and publication isolation. Existing definitions without a visualization choice keep current rendering. No search/action controls return to sidebar links.
## Pie and donut truth
Slices require nonnegative finite values of the same unit and time basis, with disjoint categories and a defined total. Missing/denied/unknown values are not zero. Percentages use the authorized represented total and must disclose partial scope in panel information and a compact state indicator. All-zero data shows an empty/zero-total state, not an invented 100-percent slice. Negative or incompatible values disable pie/donut with a short explanation and leave bars/table/number where compatible.
Top-N truncation must not imply a full distribution. An Other slice is allowed only for a known authorized remainder with exact aggregation; hidden-resource counts cannot be inferred. Category limits and grouping policy must be explicit and preserve exact authorized totals.
## Rendering
Same-unit line/area series may share one axis with stable per-device colors and a
compact legend. Series retain individual gaps; mixed units cannot share that axis.
Cadence inference is per registered source so a fast source cannot turn a slower
valid source's intervals into artificial gaps. Complete covered energy may form
disjoint per-device bars/pie/donut only when the device values match the authorized
meter total; incomplete coverage does not become an invented distribution.
Charts fill available panel dimensions in Edit and View, retaining legible geometry after drag, numeric resize and sidebar visibility changes. Labels, legends and titles remain optional and default off under minimal presentation; keyboard-accessible slice/category values and panel information remain available. Partial/stale/denied states stay explicit. Rendering changes do not trigger inactive-screen queries or reuse evidence outside its authorization scope.
Choose and record the rendering-library approach before adding a dependency; avoid an independent bespoke chart framework. Use established project frontend/dependency validation for that choice.
## Verification
Exercise two compatible renderers against the same evidence, categorical pie/donut with known totals, invalid negative/missing/zero/partial data, bounded grouping, permission revocation, small/tall panels, keyboard inspection, copy/reload/publication and legacy definitions. Preserve current navigation and validate frontend plus affected backend contracts. Keep tasks unchecked until implementation and evidence exist.
