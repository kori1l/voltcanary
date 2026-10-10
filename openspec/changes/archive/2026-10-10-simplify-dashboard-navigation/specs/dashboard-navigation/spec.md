## ADDED Requirements
### Requirement: Single dashboard navigation entry
The sidebar SHALL expose one Dashboards entry for users and administrators. It SHALL open the existing personal default route and remain active for catalog, viewer and editor routes. Catalog search SHALL remain in the compact list, accessible through All dashboards in viewer Manage settings and editor Dashboard settings. Creation SHALL be on demand and per-dashboard management SHALL reside in concrete settings. No parallel catalog workflow SHALL be added.
#### Scenario: Navigate from a working dashboard
- **WHEN** a user opens dashboard settings or editor dashboard settings
- **THEN** All dashboards opens the existing catalog without a separate My dashboards sidebar or header entry

### Requirement: Catalog is a compact list
The catalog SHALL show only its dashboard list, search/sort/filter controls and a New dashboard command. Creation and templates SHALL be shown on demand. Favorite, personal default and copy commands SHALL be in settings of the selected dashboard, using existing API behavior. Copy SHALL explain that the latest publication is copied, or the draft when unpublished.
#### Scenario: Open the list
- **WHEN** a user opens All dashboards
- **THEN** no creation form or template gallery is visible until New dashboard is selected, and per-dashboard management is available in its own settings

### Requirement: Runtime controls reside in dashboard settings
Viewer filters, period, refresh interval and manual refresh SHALL reside in the right Dashboard settings drawer, organized into Data and time, Refresh and Manage sections. Data inputs SHALL align vertically; management SHALL group navigation, preferences, copy and lifecycle commands. The viewing canvas SHALL NOT show a second runtime toolbar. Automatic refresh SHALL continue when the drawer closes and retain the existing visibility/auth/backoff safeguards.
#### Scenario: Close settings with refresh enabled
- **WHEN** a user selects an automatic refresh interval then closes settings
- **THEN** the canvas remains clean and evidence refresh continues at the selected interval

### Requirement: Available dashboards appear in sidebar
Dashboards SHALL contain child links for available active dashboards of the current actor. Published dashboards SHALL open View; unpublished dashboards SHALL open Edit. The current child SHALL be highlighted for both View and Edit. Archived dashboards SHALL remain in the list archive filter. The sidebar SHALL retain its hide/restore and mobile behavior.
#### Scenario: Select a sidebar dashboard
- **WHEN** a user selects an available dashboard beneath Dashboards
- **THEN** its appropriate View or Edit route opens and that child is highlighted without adding another My dashboards section
