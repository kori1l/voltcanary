## ADDED Requirements
### Requirement: Dashboard navigation owns discovery
The Dashboards parent entry SHALL open the existing /dashboards catalog. Child links SHALL open concrete dashboards. The sidebar SHALL contain only navigation links and SHALL NOT contain search, creation, expansion or item-action controls. Group visibility and creation SHALL be available in the catalog.
#### Scenario: Open list from navigation
- **WHEN** the user selects Dashboards
- **THEN** /dashboards opens the full list with search, filters and creation
### Requirement: Dashboard items expose commands
Each catalog list item SHALL expose an accessible actions control with default, favorites, copy, edit and permitted archive/restore commands using existing contracts. Copy SHALL preserve retry idempotency and disclose its source revision behavior.
#### Scenario: Manage an item
- **WHEN** the user opens a catalog dashboard actions control
- **THEN** commands target that dashboard without navigating to its runtime settings
### Requirement: Personal menu visibility
Users SHALL hide or show individual active dashboards in navigation and collapse or expand the dashboard group. These choices SHALL persist locally per authenticated user. Hidden dashboards SHALL remain accessible from a Hidden filter and direct URLs; hiding SHALL NOT archive them.
#### Scenario: Restore a hidden entry
- **WHEN** the user shows an item from the Hidden list
- **THEN** it reappears beneath Dashboards and remains unchanged on the server
