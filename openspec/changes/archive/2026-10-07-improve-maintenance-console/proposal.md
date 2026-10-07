# Improve maintenance scheduling, calendar and outcomes

## Why

Calendar dates and short operations are truncated, scheduling and device maintenance use different controls, the drawer surface shows underlying content, and outcome fields are ungrouped.

Source: [UI design audit](../../../../docs/target/ui-design-requirements.md).
Coverage: F15, F18, F19, F20. This is planned work; no implementation or verification is claimed complete.

## What Changes

- Readable timeline agenda and filters: Maintenance SHALL provide readable Timeline and Agenda views, untruncated date filters, bounded local timeline scrolling, persistent device context and a useful mobile Agenda.
- Shared reviewed maintenance editor: Fleet and device scheduling/editing SHALL use shared controls in opaque accessible panels, named device selection, explicit timezone, end-after-start validation, past-interval warning and a reachable footer.
- Accurate downstream impact: Maintenance impact summaries SHALL reflect suppressAlerts, excludeFromAnalytics and excludeFromMl independently and preserve their existing server semantics for creation, editing and cancellation.
- Outcome-oriented result recording: Record outcome SHALL group result, time, failure details and notes; show relevant failure fields by outcome; explain downstream ML label use; and display a saved result in the operation history.

## Impact

- Added capability: `maintenance-console-experience`.
- Implementation owners: frontend/src/maintenance/, focused device maintenance modules, typed device/maintenance clients.
- Prerequisites: `establish-console-design-system`, `fix-console-data-state-semantics`.
- Existing APIs, authorization and domain invariants remain authoritative. No breaking API, data migration, token-policy change or UI translation is proposed.
- Follow existing frontend-operator-console decomposition and typed API/storage rules. If implementation needs an additional API contract, document its exact compatible scope before changing it.
- Validation includes focused tests and browser evidence, frontend typecheck/test/build, OpenSpec validation and aligned current documentation. Unobserved audit states require populated fixtures.


## Completion

Implemented and archived on 2026-10-07. See [verification evidence](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06) and the completed task checklist. Original proposal context above records the planned scope.
