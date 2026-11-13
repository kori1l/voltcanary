Implementation ownership note (2026-10-23): existing requirements remain the
user-facing acceptance baseline. Unfinished durable-intent/state-graph/semantic
refinement scenarios are implemented and verified through the
[reliability program](../../../README.md#ai-dashboard-reliability-program);
this note does not mark those scenarios implemented or create duplicate work.

## ADDED Requirements

### Requirement: Shared preview of configurable data and effects

Pending preview integration SHALL use the common authorized renderer and exact owner definitions for configured datasets, geometry and rule evidence, exposing native/external readiness and immutable review state without inventing readings or implying unconfirmed activation.

#### Scenario: External data unavailable
- **WHEN** the dashboard references an unavailable customer data source
- **THEN** the preview reports source readiness/unknown rather than fabricated sample observations

#### Scenario: Rule remains activating
- **WHEN** a rule owner accepted configuration but evaluator readiness is pending
- **THEN** preview/chat keeps ACTIVATING distinct from ACTIVE



### Requirement: Shared widget capability catalogue
The dashboard service SHALL expose one typed catalogue of widget data capabilities, compatible visualizations and implemented presentation settings to both the editor and AI planning.

#### Scenario: Common header settings
- **WHEN** any widget is configured
- **THEN** General contains its name and header visibility in the same position and hiding the header does not remove its accessible name or editor drag controls

#### Scenario: Supported settings only
- **WHEN** a widget is selected
- **THEN** the editor offers only its implemented presentation settings and composition validates settings using that same catalogue while retaining previously saved definitions

#### Scenario: Incremental settings
- **WHEN** AI changes one supported presentation setting
- **THEN** omitted settings, data bindings and other widgets remain unchanged

### Requirement: Independent configurable assistant window
The assistant SHALL offer floating, overlay-sidebar and expanded light appearances, retain conversation and unsent input across presentation changes and minimization, and remain independent of device page content.

#### Scenario: Change mode during dashboard review
- **WHEN** a user changes window mode or position while reviewing a dashboard
- **THEN** the same conversation, unsent message and reviewed task remain available without a new planning request

### Requirement: Shared faithful preview
The assistant SHALL display the prepared dashboard definition through the ordinary dashboard canvas and widget renderer, with the same responsive layout rules as saved viewing and real authorized evidence or explicit unavailable states.

#### Scenario: Create the displayed dashboard
- **WHEN** the user confirms a prepared preview
- **THEN** creation submits the exact reviewed definition after current authorization and digest revalidation, without alternate client layout generation

#### Scenario: No measurements exist
- **WHEN** an authorized preview has no measurements
- **THEN** the corresponding real widget shows no-data and does not display generated sample values as evidence

### Requirement: Bounded conversational layout refinement
Models SHALL compose existing supported widget types independently of templates, with supported width and height choices; the owning dashboard service SHALL validate metric/source capabilities and compile all placements deterministically. Legacy template reviews SHALL remain compatible.

#### Scenario: Remove and resize panels through chat
- **WHEN** the user asks to remove events and make a temperature graph full width
- **THEN** a new reviewed task contains only the selected panels, updated responsive layouts and a matching preview while preserving equipment and other unchanged choices

#### Scenario: Invalid model output
- **WHEN** a model supplies unknown panels, duplicate panels, invalid dimensions or raw layout/query code
- **THEN** preparation rejects it or asks for clarification without silently creating a different dashboard

#### Scenario: Vehicle map and charts together
- **WHEN** an operator requests a geographical vehicle map and telemetry charts without incidents or maintenance
- **THEN** one review contains the genuine vehicle-map and requested chart widgets, using recorded sources and metrics, without requiring a template or substituting a location matrix

### Requirement: Continuous reviewed conversation
One composer SHALL accept requests and clarification, retain prior turns and answers on failures, and prevent execution of the current review while a replacement is pending.

#### Scenario: Refinement fails
- **WHEN** a provider fails during a layout refinement
- **THEN** the previous preview and entered answer remain available for retry and no dashboard is created

### Requirement: Recorded vehicle field widgets
The catalogue SHALL offer source-bound vehicle-value and vehicle-timeline widgets for recorded numeric, boolean and text fields, configurable in the editor and through AI composition. Missing fields SHALL remain unavailable and stale observations SHALL be labelled.

#### Scenario: Bus working screen
- **WHEN** a user requests speed, door state and next stop alongside a map
- **THEN** the assistant can compose actual field value and observed-state history widgets using available source fields instead of a static note or an unsupported numeric metric

### Requirement: Controllable readable presentation
Widgets SHALL support a visibility setting for titles, maps SHALL support a separate metadata visibility setting, and chart labels and markers SHALL retain readable proportions when panels resize.

#### Scenario: Minimal map
- **WHEN** title and map details are disabled
- **THEN** normal viewing and shared preview show the map without title, follow row, recorded-position summary or GPS disclosure while retaining errors and, by default, stale/truncated warnings

#### Scenario: Model adds unsupported parameters
- **WHEN** a model emits unsupported composition fields
- **THEN** the planner attempts one correction with the same model and validates the result before offering a review

### Requirement: Stable incremental dashboard refinement
Clarifications SHALL modify only explicitly supplied root fields or widget edits in the previous server-owned intent. Omitted widgets and fields SHALL be retained, and removal/order changes SHALL be explicit.

#### Scenario: Move three status widgets
- **WHEN** the operator moves the bottom three widgets to a smaller top row
- **THEN** only those placements and sizes change while the map, chart, field bindings and name are preserved

### Requirement: Exact reversible review history
The assistant SHALL allow backward and forward review navigation without another model request or execution. A conversational restore request SHALL select an authorized stored version and reuse its exact definition.

#### Scenario: Revert a bad refinement
- **WHEN** the operator selects Back or asks to restore the previous version
- **THEN** the previous reviewed definition is restored exactly and the next refinement uses that restored version as its base

#### Scenario: Recover review history
- **WHEN** the latest unexecuted dashboard task is recovered after reload
- **THEN** authorized prior review IDs are available and selecting one revalidates its current access

### Requirement: Compact value cards
Value widgets SHALL support one-row cards and an explicit compact presentation setting. Small value cards SHALL automatically use compact presentation unless explicitly disabled. The primary value and stale/unavailable state SHALL remain legible; full measurement metadata SHALL remain accessible through panel information.

#### Scenario: Shrink top status cards
- **WHEN** the operator asks to shrink the speed, door and next-stop cards by70 percent from two rows
- **THEN** the cards can become one row with smaller type and spacing while unrelated map/chart configuration remains unchanged and stale observations retain a visible short warning

### Requirement: Optional map notices
The map SHALL support an independent showMapNotices boolean, defaulting true, through editor and AI composition. Disabling it SHALL hide stale-position and truncated-history footer text while retaining map errors, attribution and full panel information.

#### Scenario: Remove map footer text
- **WHEN** the operator asks to remove the stale speed and last1000 points text below the map
- **THEN** only that map's footer notices disappear and other widget definitions, data bindings and layouts remain unchanged

### Requirement: Durable intent through preparation failures
The assistant SHALL retain explicitly supplied names and original dashboard requirements across unsuccessful preparations. Failed refinements SHALL retain the last valid review and pending requested changes until repaired or explicitly replaced.

#### Scenario: Tram naming followed by incompatible widget parameters
- **WHEN** the user supplies a name and composition preparation rejects a model-selected widget configuration
- **THEN** bounded same-model repair uses actual diagnostics and retains the name without asking for it again

### Requirement: Source-bound recorded state graphs
Vehicle timeline widgets SHALL support list and line presentations for recorded boolean/numeric field observations without requiring those fields to be registered numeric telemetry metrics. Missing observations and long gaps SHALL break lines.

#### Scenario: Doors chart and compact status at the top
- **WHEN** the user requests door history as a graph and current door state alongside speed and next stop at the top
- **THEN** a source-bound door graph and separate compact status card are prepared while the name and unrelated widgets remain intact

#### Scenario: Empty model response or redundant graph
- **WHEN** the model emits an empty creation/edit action or adds an identical field chart already present
- **THEN** preparation requests a bounded repair instead of asking for a legacy template, accepting no work, or adding redundant content
## ADDED Requirements

### Requirement: Semantic refinement compilation
The system SHALL compile bounded semantic changes into validated widget mutations and SHALL enforce preservation independently of model-generated widget ordering.

#### Scenario: Move current readings and retain histories
- **WHEN** a refinement places current speed, next stop and door state at the top and requests door history as a graph
- **THEN** the compiler reuses existing matching widgets, creates only missing current-value cards, places exactly the selected cards at the top and retains the identity, settings and relative order of the map and historical graphs

#### Scenario: Unsupported history field
- **WHEN** an operation requests a line graph for a recorded text-only field
- **THEN** preparation rejects the incompatible operation for internal repair without changing the current review or inventing a numeric metric

