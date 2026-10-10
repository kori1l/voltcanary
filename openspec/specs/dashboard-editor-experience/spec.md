# dashboard-editor-experience Specification

## Purpose
Define private dashboard creation, draft editing, responsive placement and explicit publication with accessible controls.
## Requirements
### Requirement: Personal dashboard entry and safe creation

Authorized USER users SHALL discover My and Favorite dashboards, create private drafts from blank or copy with validated identity and real preview, and explicitly select a default; an unavailable default SHALL fall back without a redirect loop.

#### Scenario: Ordinary user starts

- **GIVEN** a USER owns three devices
- **WHEN** they create a blank dashboard, publish and set it as default
- **THEN** a new session opens the same dashboard rather than requiring ADMIN navigation.

#### Scenario: Archived default

- **GIVEN** the selected default was archived
- **WHEN** Dashboard is opened
- **THEN** a safe system overview explains the fallback and offers another accessible dashboard.

### Requirement: Explicit edits autosave and publication conflicts

View SHALL be stable without drag handles; Edit SHALL support typed widget configuration, a two-second draft autosave, twenty Undo/Redo operations, explicit publication and conflict recovery without silent overwrite.

#### Scenario: Unsaved edit with network failure

- **GIVEN** a user rearranges widgets
- **WHEN** draft autosave fails and navigation is requested
- **THEN** safe metadata recovery and Keep/Discard remain available; published layout is unchanged.

#### Scenario: Concurrent published revision

- **GIVEN** another editor published newer data
- **WHEN** a stale draft receives409
- **THEN** the UI offers latest version, independent copy or reviewed reapplication, never automatic overwrite.

### Requirement: Responsive layout and non-drag editing

Layout SHALL default to twelve desktop, six tablet and one mobile column, allow validated per-screen numeric grid profiles and retain separate mobile ordering, manifest size constraints and collision prevention; equivalent move and resize commands SHALL work by keyboard and single pointer.

#### Scenario: Mobile order adjustment

- **GIVEN** a desktop dashboard is published
- **WHEN** a390px user reorders through menu controls
- **THEN** mobile order changes without overwriting desktop placement and focus remains on the moved widget.

#### Scenario: Collision and enlarged text

- **GIVEN** a chart is resized beside another widget
- **WHEN** layout collision or200percent text occurs
- **THEN** widgets do not overlap, controls remain reachable and the user can undo the placement.

### Requirement: Viewer context and refresh remain personal

Runtime filters and time SHALL remain URL or personal preference state until explicit publication, and visible read panels SHALL support manual/off/30s/1m/5m refresh with hidden pause and non-hideable stale connectivity feedback.

#### Scenario: Viewer changes interval

- **GIVEN** published defaults exist
- **WHEN** the viewer selects another period then shares a safe URL
- **THEN** published defaults are unchanged, reloading restores their context and the URL does not grant resource access.

#### Scenario: Read-only kiosk loses connectivity

- **GIVEN** an authenticated kiosk is displaying a dashboard
- **WHEN** refresh fails or the tab is hidden
- **THEN** stale timing remains visible, polling pauses when hidden and editing or business mutation commands are unavailable.

