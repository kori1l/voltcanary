# Establish a responsive console design system

## Why

Desktop forms escape their cards, mobile pages scroll horizontally, controls vary by route, and parent navigation items share the active highlight with their children.

Source: [UI design audit](../../../../docs/target/ui-design-requirements.md).
Coverage: UI-01, UI-04, UI-05, UI-06, UI-07, UI-08, UI-09, UI-10, UI-11, UI-12, UI-13, UI-14, UI-15. This is planned work; no implementation or verification is claimed complete.

## What Changes

- Responsive containment: The console SHALL contain cards, controls and actions within their allocated width, stack columns when necessary, and restrict horizontal scrolling to explicitly bounded tables or timelines.
- Consistent named controls: All console forms SHALL use shared field, select, textarea, checkbox, switch and button conventions with permanent labels, accessible names, required-state indicators, units and local error messages. Typical text controls SHALL be 40–44 pixels high and checkboxes SHALL not stretch with grid cells.
- Predictable navigation and action hierarchy: Pages SHALL use a common header/context/actions layout with one primary action per independent task block, exactly one active leaf navigation item, independently scrollable desktop navigation and compact mobile navigation.
- Accessible dialogs and guarded drafts: Dialogs and drawers SHALL use opaque surfaces, a stable header and footer, independently scrolling bodies, focus containment, Escape dismissal, focus restoration and unsaved-input protection. Consequential actions SHALL use one named confirmation dialog with object, impact, dependencies and a reason when required instead of chained browser prompts.
- Readable status and time presentation: The console SHALL use semantic status tokens with text equivalents, restrained neutral surfaces, readable hierarchy and consistent locale formatting. Time-dependent controls SHALL identify their timezone and distinguish it from display locale.

## Impact

Shared searchable entity-selector conventions keep canonical identifiers behind
readable names; raw IDs and structured config remain advanced alternatives.

- Added capability: `console-design-system`.
- Implementation owners: frontend/src/app/, frontend/src/components/, focused feature components across the console.
- Prerequisites: none; shared foundation for the other UI changes.
- Existing APIs, authorization and domain invariants remain authoritative. No breaking API, data migration, token-policy change or UI translation is proposed.
- Follow existing frontend-operator-console decomposition and typed API/storage rules. If implementation needs an additional API contract, document its exact compatible scope before changing it.
- Validation includes focused tests and browser evidence, frontend typecheck/test/build, OpenSpec validation and aligned current documentation. Unobserved audit states require populated fixtures.

## Completion

Implemented and archived on 2026-10-07. See [verification evidence](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06) and the completed task checklist. Original proposal context above records the planned scope.
