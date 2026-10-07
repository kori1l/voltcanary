# Improve alert triage, rule editing and notification settings: design

## Context and scope

Audit coverage: F21, F22, F24, F25. The audit separates browser observations from code-only states; implementation must verify both rather than assuming a populated state already passed.

## Dependencies and ownership

- Depends on [establish-console-design-system](../2026-10-07-establish-console-design-system/proposal.md).
- Depends on [fix-console-data-state-semantics](../2026-10-07-fix-console-data-state-semantics/proposal.md).

Owners: frontend/src/pages/AlertsPage.tsx, frontend/src/features/notifications/, focused alert triage/rule modules and typed alert clients. Keep route pages as composition shells and extract touched workflows into focused components/hooks/model helpers. Use typed domain clients and preserve auth/error semantics.

## Decisions

1. Keep Inbox actions visible in bounded rows and separate readable device/rule context from technical IDs; unavailable or inaccessible context must be explained without bypassing ownership.

2. Use condition-aware typed rule editors with a readable if/then summary, named scope/metric selectors, unit validation and a separate test result.

3. Keep supported lifecycle mutations in one reviewed dialog and preserve required reasons, audit and lifecycle contracts. Contract inspection found `assignedTo` in the response but no assignment mutation endpoint. Show the recorded assignee and explicit unavailability rather than inventing a frontend-only assignment command.

4. Use channel-specific labeled forms; optional recipients/parse controls advanced. Reuse existing diagnostic and test-delivery APIs; do not imply unconfigured adapters are available.

5. Quiet hours use explicit timezone and routing summary; channel options reflect configured/supported availability while preserving saved unsupported values with an explanation.

## Compatibility and failure handling

No existing domain behavior or authorization gate is relaxed. Preserve canonical IDs, enums, request fields and server validation; display names are presentation only. Retain unsaved input, partial successful data and known asynchronous job state during recoverable failures. Any new server capability discovered during implementation must have its request, response, error and compatibility contract specified before use.

## Delivery and verification

Implement shared dependencies first, then focused workflows. Reuse existing component conventions during migration and avoid parallel catch-all settings services. Test normal, loading, empty, filtered-empty, error and stale states at relevant boundaries; use 360/390/768/1024/1280/1440 px and 200% text zoom for layouts. Check keyboard, focus, long names and busy/duplicate-submit states. Cover USER/SUPPORT/ADMIN visibility with server authorization intact. Populate audit code-only states with local fixtures; keep generated evidence and secrets out of commits.

Run frontend typecheck, test and build; run OpenSpec validation and structure check. Update relevant current API/flow documentation only when behavior is implemented; the source audit remains a target requirements document. 
