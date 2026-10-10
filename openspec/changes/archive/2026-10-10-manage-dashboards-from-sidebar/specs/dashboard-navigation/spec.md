## MODIFIED Requirements
### Requirement: Single dashboard navigation entry
The sidebar SHALL expose one Dashboards entry for users and administrators. It SHALL open the existing /dashboards catalog route and remain active for catalog, viewer and editor routes. Catalog search SHALL remain in the compact list, accessible by selecting Dashboards or All dashboards in viewer and editor settings. Creation SHALL be on demand and per-dashboard management SHALL reside in concrete settings. No parallel catalog workflow SHALL be added.
#### Scenario: Navigate from a working dashboard
- **WHEN** a user opens dashboard settings or editor dashboard settings
- **THEN** All dashboards opens the existing catalog without a separate My dashboards sidebar or header entry

### Requirement: Catalog is a compact list
The compact catalog SHALL show its dashboard list, search/sort/filter controls, item actions and a New dashboard command. It SHALL open when selecting the Dashboards parent entry. Creation and templates SHALL be shown on demand. Favorite, personal default and copy commands SHALL be available through actions of the selected dashboard in the catalog list and its concrete settings, using existing API behavior. Copy SHALL explain that the latest publication is copied, or the draft when unpublished.
#### Scenario: Open the list
- **WHEN** a user opens All dashboards
- **THEN** no creation form or template gallery is visible until New dashboard is selected, and per-dashboard management is available in its own settings

### Requirement: Available dashboards appear in sidebar
Dashboards SHALL contain child links for available active dashboards of the current actor unless personally hidden from the menu. The group SHALL be collapsible without changing dashboard state. Published dashboards SHALL open View; unpublished dashboards SHALL open Edit. The current child SHALL be highlighted for both View and Edit. Archived dashboards SHALL remain in the list archive filter. The sidebar SHALL retain its hide/restore and mobile behavior.
#### Scenario: Select a sidebar dashboard
- **WHEN** a user selects an available dashboard beneath Dashboards
- **THEN** its appropriate View or Edit route opens and that child is highlighted without adding another My dashboards section

