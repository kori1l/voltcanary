## ADDED Requirements

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
