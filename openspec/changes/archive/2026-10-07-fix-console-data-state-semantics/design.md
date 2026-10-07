# Make console loading and data states truthful: design

## Context and scope

Audit coverage: UI-02, UI-03, O07, O08, F06. The audit separates browser observations from code-only states; implementation must verify both rather than assuming a populated state already passed.

## Dependencies and ownership

- Depends on [establish-console-design-system](../2026-10-07-establish-console-design-system/proposal.md).

Owners: frontend/src/pages/DashboardPage.tsx, focused device/telemetry/alerts/ML loading hooks and view models. Keep route pages as composition shells and extract touched workflows into focused components/hooks/model helpers. Use typed domain clients and preserve auth/error semantics.

## Decisions

1. Represent initial loading, fresh data, empty successful response, filtered-empty, error, stale retained data and insufficient evidence as distinct typed states.

2. Compose independently loaded dashboard sections; one failed dependency must not replace successfully loaded content with zero arrays.

3. Retain known data with its observation time and retry action. Health and risk labels derive from evidence, not defaults or registry ACTIVE state.

4. Keep chart gaps and ML uncertainty visible. Aggregated rates require a denominator, observation window and actual samples; never silently manufacture 0%.

## Compatibility and failure handling

No existing domain behavior or authorization gate is relaxed. Preserve canonical IDs, enums, request fields and server validation; display names are presentation only. Retain unsaved input, partial successful data and known asynchronous job state during recoverable failures. Any new server capability discovered during implementation must have its request, response, error and compatibility contract specified before use.

## Delivery and verification

Implement shared dependencies first, then focused workflows. Reuse existing component conventions during migration and avoid parallel catch-all settings services. Test normal, loading, empty, filtered-empty, error and stale states at relevant boundaries; use 360/390/768/1024/1280/1440 px and 200% text zoom for layouts. Check keyboard, focus, long names and busy/duplicate-submit states. Cover USER/SUPPORT/ADMIN visibility with server authorization intact. Populate audit code-only states with local fixtures; keep generated evidence and secrets out of commits.

Run frontend typecheck, test and build; run OpenSpec validation and structure check. Update relevant current API/flow documentation only when behavior is implemented; the source audit remains a target requirements document. 

