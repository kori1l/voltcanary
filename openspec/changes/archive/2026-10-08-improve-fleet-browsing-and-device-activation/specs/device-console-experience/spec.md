## ADDED Requirements

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
