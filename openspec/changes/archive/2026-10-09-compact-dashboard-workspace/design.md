## Context
Repeated title/status/instruction/settings/screen toolbars delay the canvas. Use Grafana's toolbar and panel-menu interaction as reference, within the existing console components.
## Goals / Non-Goals
Start panels near the top, retain all workflows, keep responsive and keyboard access. No chart-library migration or data semantics change.
## Decisions
Use a compact dashboard header and actions row; keep save/publication state there. Open dashboard, screen, panel and filter settings in a right-side variant of the existing focus-managed native Dialog. Put screen settings next to tabs and card actions in compact controls. Instructions remain accessible through settings and live movement announcements. Viewer preferences and lifecycle commands remain available from its options menu. Exceptional recovery/conflict/errors remain explicit rather than hidden. A shell-owned navigation visibility context exposes hide/show controls; hiding the left sidebar releases its grid column. Visibility is session component state, with a restore button available after navigation to other pages.
## Risks / Trade-offs
Menus reduce visual noise but must stay keyboard accessible and visible on touch. Verify all screen, configuration and publication flows and actual header/canvas geometry at desktop and mobile widths.
## Migration Plan
No migration; saved definitions and ownership unchanged.
