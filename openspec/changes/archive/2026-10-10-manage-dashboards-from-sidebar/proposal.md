## Why
The Dashboards parent menu currently opens a personal default instead of the dashboard list. Users want the catalog reachable directly from that entry and item commands in the catalog.
## What Changes
Make Dashboards open /dashboards. Keep child links for specific dashboards, keep the sidebar as links only, and add catalog item actions for preferences, copy, edit, archive/restore and local menu visibility. Group visibility and creation are controlled from the catalog. Keep search in the catalog only. Reuse existing commands instead of a parallel catalog drawer.
## Capabilities
### New Capabilities
- `dashboard-sidebar-management`: item commands and personal menu visibility.
### Modified Capabilities
- `dashboard-navigation`: parent entry opens the existing catalog and list rows expose actions.
## Impact
Frontend navigation, dashboard catalog, user-local preferences and console documentation. Backend contracts remain unchanged.
