## ADDED Requirements
### Requirement: Visualization selection is separate from data queries
Typed widgets SHALL expose compatible number, line, area, vertical/horizontal bar, stacked bar, pie and donut presentation choices according to their manifest and actual evidence shape. The existing palette SHALL preview choices and the right Presentation drawer SHALL configure them. Switching visualization SHALL preserve binding, unit, calculation, time, screen and layout, remain undoable and survive draft reload, copy and explicit publication. Legacy definitions SHALL retain their current renderer.
#### Scenario: Change a compatible categorical view
- **WHEN** the editor changes an authorized categorical count panel from bars to donut
- **THEN** the same data and scope render as a donut without changing the published dashboard until publication
#### Scenario: Unsupported representation
- **WHEN** the selected widget supplies only one scalar or overlapping min/mean/max statistics
- **THEN** pie/donut are unavailable with a concise explanation and compatible choices remain usable
### Requirement: Part-to-whole charts preserve evidence semantics
Pie and donut SHALL use finite nonnegative values of compatible unit/time basis, disjoint categories and a defined authorized total. Missing, unknown and denied values SHALL NOT become zero. Partial or truncated data SHALL NOT imply complete coverage. Other grouping SHALL require a known exact authorized remainder without exposing inaccessible categories. Zero totals SHALL show an explicit empty/zero-total state.
#### Scenario: Known categorical totals
- **GIVEN** disjoint authorized counts of 2, 3 and 5
- **WHEN** the panel displays a pie or donut
- **THEN** slices represent 20, 30 and 50 percent of that authorized total
#### Scenario: Missing, invalid or truncated data
- **WHEN** categories are unknown, values negative, all counts zero or a ranking omits an unknown remainder
- **THEN** the panel explains its unavailable, partial or zero-total state and never invents a complete distribution
### Requirement: Diagram rendering preserves compact responsive dashboards
Diagrams SHALL use available panel width and height in Edit and View, support keyboard-accessible values and keep optional titles/legends/labels under minimal presentation defaults. Compact exceptional indicators and panel information SHALL retain authorized provenance and denominators. Visualization selection SHALL NOT introduce sidebar controls, permanent canvas toolbars or inactive-screen reads.
#### Scenario: Resize or hide navigation
- **WHEN** a user resizes a chart panel or hides/restores left navigation
- **THEN** geometry fits the available area and saved chart type, screen contents and accessible values remain intact
