# device-console-experience Specification

## Purpose
Define readable inventory, recoverable onboarding, CSV preview, source/sensor configuration and preset-first simulation workflows.
## Requirements
### Requirement: Scannable fleet inventory

Devices SHALL default to recognizable appliance icon tiles grouped by location and provide an optional compact fleet table, with name, location, registry state, connection freshness and risk; search, filters and first-use states SHALL remain readable across widths.

#### Scenario: An operator opens the fleet

- **WHEN** Devices is opened
- **THEN** location-grouped tiles identify appliance types through icons and names, and the operator can switch to a table without losing filters.

#### Scenario: An operator scans a large fleet

- **WHEN** the table view or filters are selected
- **THEN** devices can be compared without opening each card and inactive registration is not confused with missing telemetry.

### Requirement: Recoverable four-step onboarding

The Basics, Location, Telemetry and Review wizard SHALL distinguish current/completed steps, retain draft state when going back, keep actions reachable and summarize source mode and simulator start behavior before creation.

#### Scenario: Setup fails after device creation

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator retries the unfinished source or simulator step
- **THEN** created resources and the failure are shown and setup resumes using their recorded IDs rather than creating a duplicate device.

### Requirement: Clear location and source choices

Onboarding SHALL separate existing/new location selection, group optional building/floor/room/zone/tags fields, and explain source-specific required and advanced parameters.

#### Scenario: An operator changes location or source mode

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator moves backward and forward
- **THEN** compatible values are retained, incompatible required fields are explained and review reflects the current choices.

### Requirement: Previewable CSV imports

Device and source imports SHALL accept files with a downloadable template, supported-column and row preview, row-numbered validation and separate accepted/rejected results. Textarea entry SHALL remain an advanced alternative.

#### Scenario: A CSV contains invalid rows

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator previews and imports the file
- **THEN** preview identifies errors before mutation and the result preserves server-reported partial acceptance without calling the whole import a success.

### Requirement: Readable source sensor and analytics configuration

Configuration SHALL group source and sensor inventories with focused editors, named metric mapping and units, typed known analytics parameters and advanced JSON preserving unknown keys. Credential rotation/revocation SHALL explain the effect on ingestion.

#### Scenario: A configured device is edited

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** a sensor mapping, analytics setting or source credential operation is reviewed
- **THEN** supported values and units are visible and unrelated configuration keys remain intact; consequential token actions require a clear impact review.

### Requirement: Preset-first simulator workspace

Demo Tools SHALL expose presets before advanced parameters, show units, disclose anomaly settings when enabled and distinguish draft, saved configuration and running state. Validate, Save, Start/Stop and run history SHALL remain accessible.

#### Scenario: A simulator draft differs from its saved config

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** an operator reviews or saves it while a run exists
- **THEN** the UI identifies which configuration the run uses, shows validation results and does not represent unsaved changes as active.

### Requirement: Reachable fleet pages and stable filters

Inventory and selectors SHALL support reaching records beyond the first page, preserve stable contract-defined status choices on empty results, and display totals only when authoritative.

#### Scenario: Device beyond first page

- **GIVEN** more than one hundred authorized devices exist
- **WHEN** the user searches or moves to the next page
- **THEN** device 101 is selectable, filter choices do not disappear and a page length is not presented as the full fleet count.

### Requirement: Registration to first measurement workflow

Successful registration SHALL expose source configuration, expected first measurement and an explicit sender or simulator next step without claiming connectivity from ACTIVE registration.

#### Scenario: REST source registration

- **GIVEN** device and source creation succeeded
- **WHEN** the wizard completes
- **THEN** the operator sees source instructions and a reviewed credential action; awaiting measurement becomes measured only after telemetry evidence arrives.

#### Scenario: Retry after partial creation

- **GIVEN** a canonical device ID was returned before a source failure
- **WHEN** setup is retried
- **THEN** the existing device continuation is used rather than a duplicate creation.

### Requirement: Compact operational console filters
Alerts and maintenance SHALL place their primary filters in compact top toolbars. Paged entity lookup, refresh, sorting and secondary options SHALL remain accessible without occupying the default viewport. Alert count summaries SHALL be compact so the incident inbox remains visible on the first desktop screen. Query scope, paging, incident actions and maintenance interval semantics SHALL remain unchanged.
#### Scenario: Operator opens alert inbox
- **WHEN** the alert inbox is opened on desktop
- **THEN** primary filters and a compact count summary precede visible incident rows, while refresh/sorting options are expandable and incident actions remain usable.
#### Scenario: Initial alert read is replayed
- **WHEN** React replays the initial loading effects and the server returns successful incident and summary responses
- **THEN** the replacement reads complete, recorded counts and incidents become visible, and Refresh is enabled instead of remaining in a loading state.
#### Scenario: Operator opens maintenance
- **WHEN** the maintenance calendar is opened
- **THEN** search, interval, location, type and status filters form a top toolbar and the calendar retains the full working width.

### Requirement: Inventory-first controls and optional evidence
The inventory SHALL keep search, location, registration status and view selection visible in a compact top toolbar, with paged location search and refresh settings opening in anchored popovers. Active filters SHALL remain disclosed and clearable. Successfully loaded authorized devices SHALL remain visible when optional reference or evidence reads fail. Missing risk assessments SHALL be distinguished from real service or access failures without inventing healthy zero scores. Normal cards SHALL avoid repeated technical read summaries. Multi-page navigation SHALL remain available and counts SHALL identify their page scope.
#### Scenario: Small fleet with no calculated risk
- **GIVEN** seven authorized devices and one risk endpoint returning 404 for absent assessment
- **WHEN** inventory loads
- **THEN** all seven devices are visible, that device shows risk not calculated, and unnecessary page-navigation buttons are omitted.
#### Scenario: Optional references or risk service fails
- **GIVEN** devices load but reference or risk reads fail
- **WHEN** inventory renders
- **THEN** devices remain visible, failures retain explicit retry controls, and access-denied evidence is purged rather than represented as missing or zero risk.
#### Scenario: Advanced filtering and paging
- **GIVEN** a fleet spanning several pages
- **WHEN** the operator opens filters, changes location/status/search or switches view
- **THEN** existing URL filters, debounce and page reset behavior are retained and later pages remain reachable.

### Requirement: Local simulator availability feedback
Simulator reference reads SHALL occur only in simulator setup or its device workspace. A compact accessible icon SHALL distinguish available, unavailable, checking and unchecked service state, with an explanatory tooltip and a retry action in the simulator context. Service availability SHALL NOT imply a running simulation. Unrelated inventory SHALL NOT display simulator reference failures.
#### Scenario: Simulator service is stopped
- **GIVEN** normal inventory is opened while the simulator service is unavailable
- **WHEN** the operator later opens simulator setup or Demo Tools
- **THEN** inventory remains unaffected and the simulator displays its unavailable indicator with a local retry action.

