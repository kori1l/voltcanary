# Improve authentication, account security and administration

## Why

Authentication screens subordinate the task to the logo, recovery exposes technical token entry, password forms overflow, and Account mixes personal settings with administration.

Source: [UI design audit](../../../../docs/target/ui-design-requirements.md).
Coverage: F01, F02, F03, F04, F05, F23, F37. This is planned work; no implementation or verification is claimed complete.

## What Changes

- Task-focused authentication: Login and registration SHALL use a common shell with a real task heading, restrained branding, permanent labels, password visibility, password rules before submission, Enter support and duplicate-submit prevention.
- Guided recovery and verification: Recovery and verification SHALL explain the next step, accept incoming token links, preserve a manual-entry fallback, distinguish expired or invalid tokens and offer repeat request and sign-in navigation. Forgotten-password outcomes SHALL not reveal whether the email exists.
- Unified profile and security settings: Personal settings SHALL separate profile, security and notifications into readable sections and use the same labeled password workflow wherever exposed. Password forms SHALL remain inside their cards with clear success and error feedback.
- Explicit administration actions: Admin SHALL be a distinct user-access surface with searchable stable user rows, named role/status controls, secondary identity details and explicit confirmed Apply actions. Server authorization SHALL remain authoritative for USER, SUPPORT and ADMIN.
- Readable session operations: Session details SHALL present status, creation, expiry and available context before raw IDs; revoke controls SHALL name the affected user and session scope with clear consequences and result feedback.

## Impact

- Added capability: `account-auth-console-experience`.
- Implementation owners: frontend/src/pages/{Login,Register,ForgotPassword,ResetPassword,VerifyEmail,Settings,Account}Page.tsx, frontend/src/auth/.
- Prerequisites: `establish-console-design-system`.
- Existing APIs, authorization and domain invariants remain authoritative. No breaking API, data migration, token-policy change or UI translation is proposed.
- Follow existing frontend-operator-console decomposition and typed API/storage rules. If implementation needs an additional API contract, document its exact compatible scope before changing it.
- Validation includes focused tests and browser evidence, frontend typecheck/test/build, OpenSpec validation and aligned current documentation. Unobserved audit states require populated fixtures.


## Completion

Implemented and archived on 2026-10-07. See [verification evidence](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06) and the completed task checklist. Original proposal context above records the planned scope.
