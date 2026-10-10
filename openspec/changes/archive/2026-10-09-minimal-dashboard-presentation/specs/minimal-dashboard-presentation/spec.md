## ADDED Requirements
### Requirement: Minimal visualization defaults
Dashboard panels SHALL default to visualization content without normal retrieval text, technical prose or provenance rows. Users SHALL be able to enable titles, legends, calculation annotations, comparisons, axes and inline evidence details in panel settings. Complete evidence information SHALL remain available on demand.
#### Scenario: Render a healthy chart
- **WHEN** a saved chart has no annotation overrides
- **THEN** the panel shows its chart without visible title, legend, sample-count paragraph, provenance or measurement-time footer while retaining its accessible identity and description
#### Scenario: Enable annotations
- **WHEN** the user enables presentation annotations and saves the draft
- **THEN** only the chosen annotations appear and their settings survive reload
#### Scenario: Exceptional evidence
- **WHEN** evidence is stale, partial, estimated, denied, unavailable or missing
- **THEN** a compact state indicator remains visible and denied evidence never displays a prior value
### Requirement: Compact screen selection
The dashboard SHALL retain all named working screens using a compact selector rather than a permanent row of screen tabs. The editor SHALL consolidate primary commands in the header and preserve screen creation, settings and active-screen-only reads.
#### Scenario: Select another screen
- **WHEN** a user selects a different working screen
- **THEN** its panels appear and the URL and read scope follow that screen without requesting inactive-screen evidence
