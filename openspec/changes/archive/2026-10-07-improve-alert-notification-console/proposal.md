# Improve alert triage, rule editing and notification settings

## Why

Populated Inbox actions escape the panel, event context is unresolved or truncated, rules have unlabeled inputs, and notification settings expose technical fields without routing clarity.

Source: [UI design audit](../../../../docs/target/ui-design-requirements.md).
Coverage: F21, F22, F24, F25. This is planned work; no implementation or verification is claimed complete.

## What Changes

- Bounded readable alert triage: Inbox SHALL show sortable severity/time/status rows with device, rule, age and reachable actions, retain metric/time-window deep links and explain unavailable context. Errors SHALL not imply no active alerts.
- Reviewed alert lifecycle actions: Assignment and resolution SHALL use named controls and one reviewed dialog with the affected alert, action, reason and result feedback while preserving audit requirements and authorization.
- Condition-aware rule editing and testing: Rule creation/editing SHALL use permanent labels, supported metric selection, units, condition-specific parameters, readable if/then text and named scope/channel selection. Test SHALL have an explicit interval and a result separate from Create.
- Channel-specific readable notification setup: Webhook, Email and Telegram editors SHALL group identity, recipients and advanced parameters with permanent labels and credential-reference guidance. Creation, availability, enable state, diagnostic status and test delivery SHALL be distinct operations using typed APIs without exposing provider secrets.
- Understandable routing and quiet hours: Preferences SHALL group severities, channel types and quiet hours with normal-sized labeled time controls, timezone selection, critical-bypass explanation and a routing summary beside Save.

## Impact

- Added capability: `alert-notification-console-experience`.
- Implementation owners: frontend/src/pages/AlertsPage.tsx, frontend/src/features/notifications/, focused alert triage/rule modules and typed alert clients.
- Prerequisites: `establish-console-design-system`, `fix-console-data-state-semantics`.
- Existing APIs, authorization and domain invariants remain authoritative. No breaking API, data migration, token-policy change or UI translation is proposed.
- Follow existing frontend-operator-console decomposition and typed API/storage rules. If implementation needs an additional API contract, document its exact compatible scope before changing it.
- Validation includes focused tests and browser evidence, frontend typecheck/test/build, OpenSpec validation and aligned current documentation. Unobserved audit states require populated fixtures.


## Completion

Implemented and archived on 2026-10-07. See [verification evidence](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06) and the completed task checklist. Original proposal context above records the planned scope.
