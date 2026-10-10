# minimal-dashboard-presentation Specification

## Purpose
Define clean visualization defaults, optional annotations and accessible evidence information while preserving exceptional states.
## Requirements
### Requirement: Minimal visualization defaults
In View, dashboard panels SHALL default to visualization content without normal retrieval text, technical prose or provenance rows. Titles, legends, calculation annotations, comparisons and inline evidence details SHALL default off; chart axes SHALL default on and remain optional. Users SHALL configure these options in the right settings drawer. Complete authorized evidence, including scope, time, methods, denominators and provenance, SHALL remain available through keyboard-accessible panel information. Edit SHALL retain a visible compact title/drag header and controls for direct manipulation. These visualization defaults SHALL apply to future energy, tariff, operational and shared dashboard panels as well as existing panels.
#### Scenario: Render a healthy chart
- **WHEN** a saved chart is viewed without annotation overrides
- **THEN** the panel shows its chart without visible title, legend, sample-count paragraph, provenance or measurement-time footer while retaining its accessible identity and description
#### Scenario: Enable annotations
- **WHEN** the user enables presentation annotations and saves the draft
- **THEN** only the chosen annotations appear and their settings survive reload
#### Scenario: Exceptional evidence
- **WHEN** evidence is stale, partial, estimated, denied, unavailable or missing
- **THEN** a compact state indicator remains visible and denied evidence never displays a prior value

#### Scenario: Inspect a clean panel
- **WHEN** the user opens information for a panel with annotations disabled
- **THEN** its authorized scope, measurement basis and available evidence details are disclosed without changing the saved presentation or issuing an additional evidence query

### Requirement: Compact screen selection
The dashboard SHALL retain all named working screens using a compact selector rather than a permanent row of screen tabs. The editor SHALL consolidate primary commands in the header and preserve screen creation, settings and active-screen-only reads.
#### Scenario: Select another screen
- **WHEN** a user selects a different working screen
- **THEN** its panels appear and the URL and read scope follow that screen without requesting inactive-screen evidence
