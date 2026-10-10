## ADDED Requirements
### Requirement: Clear panel information and a single catalog entry
Dashboard cards SHALL omit repeated Add buttons and retain Edit/information controls. The header SHALL remain the widget catalog entry. Information SHALL use readable label/value fields, wrapped long values and expandable bounded record lists, retain authorized provenance and SHALL NOT request additional evidence or display denied prior values.
#### Scenario: Inspect nested evidence
- **WHEN** the user opens information containing equipment IDs, timestamps and nested records
- **THEN** fields remain readable within the drawer, record counts and additional-record controls are explicit and values do not overflow the page
### Requirement: Standard sidebar controls
The workspace SHALL have one accessible SVG navigation toggle. Desktop SHALL hide/restore the sidebar with distinct open/close icon states. Mobile SHALL open a native left navigation drawer with an accessible close button, focus containment, Escape and focus restoration; route changes SHALL close the drawer.
#### Scenario: Toggle desktop navigation
- **WHEN** a user toggles navigation from the header
- **THEN** the sidebar hides/restores, its width is released and no duplicate sidebar toggle is rendered
#### Scenario: Open mobile navigation
- **WHEN** a user opens navigation on a narrow viewport
- **THEN** a left drawer contains the navigation links and close control, and closing returns focus to the header trigger
