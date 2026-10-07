# Improve contextual AI assistant and provider configuration

## Why

Assistant setup exposes context JSON and internal safety notes, plans have competing confirmation commands, and providers mix connection, model and advanced tuning in one form.

Source: [UI design audit](../../../../docs/target/ui-design-requirements.md).
Coverage: F35, F36. This is planned work; no implementation or verification is claimed complete.

## What Changes

- Contextual assistant requests: The page and drawer assistant SHALL show current device/alert/interval context and allow ordinary requests without editing JSON. Raw context and internal safety diagnostics SHALL remain secondary advanced detail.
- Clear plan and execution review: Plans SHALL prioritize named affected objects, proposed changes and consequences with separate read-only and mutating execution paths. Mutating actions SHALL require the existing confirmation review; bypass-style commands SHALL not be an ordinary option.
- Capability-aware provider and model setup: Provider setup SHALL separate connection, model selection and advanced tuning, use capability-supported fields, label timeouts with understandable units and show real connection-test results.
- Safe understandable secret rotation: Provider secret state and rotation SHALL use separate reviewed controls, indicate configured/missing status without disclosure and preserve existing encryption and authorization boundaries. Secrets SHALL not enter persisted drafts, request context or diagnostic output.

## Impact

- Added capability: `ai-assistant-console-experience`.
- Implementation owners: frontend/src/aiAgent/, frontend/src/pages/AiAgentPage.tsx, frontend/src/pages/AiAgentSettingsPage.tsx, typed ai-agent clients.
- Prerequisites: `establish-console-design-system`.
- Existing APIs, authorization and domain invariants remain authoritative. No breaking API, data migration, token-policy change or UI translation is proposed.
- Follow existing frontend-operator-console decomposition and typed API/storage rules. If implementation needs an additional API contract, document its exact compatible scope before changing it.
- Validation includes focused tests and browser evidence, frontend typecheck/test/build, OpenSpec validation and aligned current documentation. Unobserved audit states require populated fixtures.


## Completion

Implemented and archived on 2026-10-07. See [verification evidence](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06) and the completed task checklist. Original proposal context above records the planned scope.
