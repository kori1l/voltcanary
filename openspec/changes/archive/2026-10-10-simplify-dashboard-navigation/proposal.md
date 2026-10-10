## Why
Dashboard and My dashboards duplicate top-level navigation and add clutter.
## What Changes
- Use one Dashboards sidebar entry, retaining the default-dashboard route.
- Keep a compact list; move dashboard management into concrete settings and creation/templates into an on-demand drawer.
- Show available dashboards as sidebar children, with active selection and unpublished drafts opening in Edit.
- Structure runtime and management settings in the right drawer; remove the canvas toolbar.
- Rename user-facing My dashboards labels; preserve routes and stored data.
## Capabilities
### New Capabilities
- `dashboard-navigation`: One dashboard navigation entry with catalog access in settings.
### Modified Capabilities
## Impact
Frontend navigation and labels only; no API, data or permissions changes.
