# Improve contextual AI assistant and provider configuration: design

## Context and scope

Audit coverage: F35, F36. The audit separates browser observations from code-only states; implementation must verify both rather than assuming a populated state already passed.

## Dependencies and ownership

- Depends on [establish-console-design-system](../2026-10-07-establish-console-design-system/proposal.md).

Owners: frontend/src/aiAgent/, frontend/src/pages/AiAgentPage.tsx, frontend/src/pages/AiAgentSettingsPage.tsx, typed ai-agent clients. Keep route pages as composition shells and extract touched workflows into focused components/hooks/model helpers. Use typed domain clients and preserve auth/error semantics.

## Decisions

1. Derive visible context from the current device/alert/interval route and support review without requiring JSON knowledge. Keep technical diagnostics and raw parameters in Advanced.

2. Show plan objects, proposed changes and effects before execution. Distinguish Run read-only from reviewed Confirm and execute; never encourage bypassing confirmation.

3. Use provider capability data to drive fields and supported model features. Separate connection, model tuning and secret rotation; never leak secrets into plans, logs or drafts.

4. Keep backend ownership, authorization, audited execution and confirmation requirements unchanged. UI review cannot authorize tools beyond the existing user's scope.

## Compatibility and failure handling

No existing domain behavior or authorization gate is relaxed. Preserve canonical IDs, enums, request fields and server validation; display names are presentation only. Retain unsaved input, partial successful data and known asynchronous job state during recoverable failures. Any new server capability discovered during implementation must have its request, response, error and compatibility contract specified before use.

## Delivery and verification

Implement shared dependencies first, then focused workflows. Reuse existing component conventions during migration and avoid parallel catch-all settings services. Test normal, loading, empty, filtered-empty, error and stale states at relevant boundaries; use 360/390/768/1024/1280/1440 px and 200% text zoom for layouts. Check keyboard, focus, long names and busy/duplicate-submit states. Cover USER/SUPPORT/ADMIN visibility with server authorization intact. Populate audit code-only states with local fixtures; keep generated evidence and secrets out of commits.

Run frontend typecheck, test and build; run OpenSpec validation and structure check. Update relevant current API/flow documentation only when behavior is implemented; the source audit remains a target requirements document. 

