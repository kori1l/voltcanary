# Improve maintenance scheduling, calendar and outcomes: design

## Context and scope

Audit coverage: F15, F18, F19, F20. The audit separates browser observations from code-only states; implementation must verify both rather than assuming a populated state already passed.

## Dependencies and ownership

- Depends on [establish-console-design-system](../2026-10-07-establish-console-design-system/proposal.md).
- Depends on [fix-console-data-state-semantics](../2026-10-07-fix-console-data-state-semantics/proposal.md).

Owners: frontend/src/maintenance/, focused device maintenance modules, typed device/maintenance clients. Keep route pages as composition shells and extract touched workflows into focused components/hooks/model helpers. Use typed domain clients and preserve auth/error semantics.

## Decisions

1. Share device and fleet maintenance editors and policy explanations while keeping existing per-device windows and operation grouping contracts.

2. Timeline scrolls locally with device names pinned; mobile favors Agenda. Short intervals retain readable titles or accessible expanded summaries.

3. Use timezone-aware exact inputs, end-after-start checks, past-window warnings and impact summaries derived from actual flags, not fixed text.

4. Outcome fields adapt to the selected outcome; do not change downstream failure-label or exclusion meanings.

## Compatibility and failure handling

No existing domain behavior or authorization gate is relaxed. Preserve canonical IDs, enums, request fields and server validation; display names are presentation only. Retain unsaved input, partial successful data and known asynchronous job state during recoverable failures. Any new server capability discovered during implementation must have its request, response, error and compatibility contract specified before use.

## Delivery and verification

Implement shared dependencies first, then focused workflows. Reuse existing component conventions during migration and avoid parallel catch-all settings services. Test normal, loading, empty, filtered-empty, error and stale states at relevant boundaries; use 360/390/768/1024/1280/1440 px and 200% text zoom for layouts. Check keyboard, focus, long names and busy/duplicate-submit states. Cover USER/SUPPORT/ADMIN visibility with server authorization intact. Populate audit code-only states with local fixtures; keep generated evidence and secrets out of commits.

Run frontend typecheck, test and build; run OpenSpec validation and structure check. Update relevant current API/flow documentation only when behavior is implemented; the source audit remains a target requirements document. 

