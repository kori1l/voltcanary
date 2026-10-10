# Named working screens and deliberate widget placement

## Why

The user explicitly prioritizes dashboard usability: Monitoring, Analytics and
Incidents need separate working screens, each with its own graphs and fields.
An added widget must have a deliberate position relative to another widget;
left/right commands must not silently collapse the whole layout into a column.

## What Changes

- Additive named screen definitions, accessible switching and per-screen widgets.
- Contextual add/move above, below, left and right with responsive bounds and
  deterministic collision displacement, undo and independent mobile layouts.
- Readable KPI/graph/table content, compact editor controls and active-screen reads.
- Preserve authorized private drafts, immutable publication, recovery and legacy
  single-screen definitions. Screen labels never grant persona or team rights.

## Capabilities

### New Capabilities

- `dashboard-working-screens`: Named working screens and explicit relative placement.

### Modified Capabilities

- Existing private/editor/query contracts remain compatible; new screen fields
  extend schema1 without importing resource rights or changing evidence math.

## Dependencies And Priority

Private domain, query engine, visual editor and task templates are implemented
and promoted. User instruction on2026-10-09 places this refinement before the
remaining energy/workspace/team/tariff/operational queue. Archived changes remain
archived; this change owns the new behavior.

## Impact

Dashboard JSON definition/validation, frontend dashboard feature models, editor,
viewer and business panel rendering; current API/flow and target mapping docs.
No new grants, billing, public sharing, raw telemetry persistence or deployment
exposure. Existing bounds (24 widgets total,128KiB) remain enforced.
