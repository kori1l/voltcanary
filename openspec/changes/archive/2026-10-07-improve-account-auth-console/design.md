# Improve authentication, account security and administration: design

## Context and scope

Audit coverage: F01, F02, F03, F04, F05, F23, F37. The audit separates browser observations from code-only states; implementation must verify both rather than assuming a populated state already passed.

## Dependencies and ownership

- Depends on [establish-console-design-system](../2026-10-07-establish-console-design-system/proposal.md).

Owners: frontend/src/pages/{Login,Register,ForgotPassword,ResetPassword,VerifyEmail,Settings,Account}Page.tsx, frontend/src/auth/. Keep route pages as composition shells and extract touched workflows into focused components/hooks/model helpers. Use typed domain clients and preserve auth/error semantics.

## Decisions

1. Share the auth shell and password-field controls; retain existing identity APIs, session refresh and server-side role enforcement.

2. Support token-bearing incoming reset/verification links without changing token verification contracts. Retain a clearly labeled manual-token fallback; do not imply email delivery where the configured service cannot provide it.

3. Separate personal profile/security from Admin user access and sessions. Preserve /account deep-link compatibility with explicit Admin labeling.

4. Role/status updates use a draft and explicit Apply confirmation; opening or selecting a value must not silently alter access. Never expose session credentials in details.

## Compatibility and failure handling

No existing domain behavior or authorization gate is relaxed. Preserve canonical IDs, enums, request fields and server validation; display names are presentation only. Retain unsaved input, partial successful data and known asynchronous job state during recoverable failures. Any new server capability discovered during implementation must have its request, response, error and compatibility contract specified before use.

## Delivery and verification

Implement shared dependencies first, then focused workflows. Reuse existing component conventions during migration and avoid parallel catch-all settings services. Test normal, loading, empty, filtered-empty, error and stale states at relevant boundaries; use 360/390/768/1024/1280/1440 px and 200% text zoom for layouts. Check keyboard, focus, long names and busy/duplicate-submit states. Cover USER/SUPPORT/ADMIN visibility with server authorization intact. Populate audit code-only states with local fixtures; keep generated evidence and secrets out of commits.

Run frontend typecheck, test and build; run OpenSpec validation and structure check. Update relevant current API/flow documentation only when behavior is implemented; the source audit remains a target requirements document. 

