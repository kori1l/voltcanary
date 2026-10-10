# Working screens and relative placement

## Screen model

Schema1 adds optional screens[{id,title,widgetIds}] (maximum8). Missing/empty
screens is the legacy main screen. Explicit screens own each widget exactly
once. Ordered screens define the starting screen; names are1–80 characters.
Sections may carry screenId. Twenty-four widgets remain the dashboard-wide
query/storage bound. No screen is an authorization role: Monitoring, Analytics
and Incidents describe tasks, with the same renewed owner/resource checks.

Screen tabs use the existing accessible manual-activation Tabs and mobile
selector. Viewer URL screenId identifies an authorized published screen; unknown
IDs fall back visibly to the starting screen. Kiosk reads the selected screen.
Draft edits create/rename/reorder screens, move widgets between screens and
remove a screen with explicit widget preservation. Publish commits all screens;
publication validation covers inactive screens without querying their facts.

## Placement and rendering

Contextual Add/Move relative to a selected widget exposes above/below/left/right.
Side-by-side placement checks manifest minimum widths against12/6/1 columns;
unavailable directions explain the width restriction. The selected placement is
pinned first; only colliding neighbours move down. Other screen/section layouts
remain unchanged. Mobile order is independent. Keyboard and pointer use the same
commands, with an announcement and focus on the placed widget. Undo restores the
entire screen/layout state. Empty screens offer Add at the start or task presets.

Editor metadata, screen controls and catalog are compact disclosures/toolbars;
the active canvas stays the main work area. KPI cards emphasize recorded numbers
and units; series use existing accessible chart primitives and observed samples,
breaking on gaps. Technical data stays in details. Unknown/no-data/stale/denied,
zero values and provenance retain their existing meanings.

## External design references

[Grafana groupings](https://grafana.com/docs/grafana/latest/visualizations/dashboards/build-dashboards/create-dashboard/dashboard-groupings/)
documents tabs/rows and custom/auto layouts. [Power BI reports](https://learn.microsoft.com/en-us/power-bi/create-reports/power-bi-reports-overview)
are multi-page documents containing visualizations and fields. These inform the
interaction pattern; VoltCanary retains its own ownership and typed query model.

## Verification

Legacy JSON/draft/publication/copy/rollback round trips; invalid/duplicate screen
membership; relative placement near boundaries and collisions; unaffected
screens and desktop/mobile layouts; undo, focus and keyboard switching; active
screen-only reads and stale/denied purge. A real browser creates Monitoring,
Analytics and Incidents, gives them distinct panels, publishes/reloads and
places cards in all valid directions at360/390/768/1024/1280/1440 widths.
Screens are task navigation, not team publication or measured capacity claims.
