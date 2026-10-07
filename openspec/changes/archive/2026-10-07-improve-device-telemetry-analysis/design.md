# Improve device overview, telemetry and analytics workflows: design

## Context and scope

Audit coverage: F11, F12, F13, F14. The audit separates browser observations from code-only states; implementation must verify both rather than assuming a populated state already passed.

## Dependencies and ownership

- Depends on [establish-console-design-system](../2026-10-07-establish-console-design-system/proposal.md).
- Depends on [fix-console-data-state-semantics](../2026-10-07-fix-console-data-state-semantics/proposal.md).

Owners: frontend/src/devices/details/, frontend/src/features/telemetry/, focused analytics and alert view models. Keep route pages as composition shells and extract touched workflows into focused components/hooks/model helpers. Use typed domain clients and preserve auth/error semantics.

## Decisions

1. Make Overview a concise state/reason/freshness/next-action summary and keep detailed charts on Telemetry.

2. Keep canonical metric keys, selected counts, accessible period fields and event context in typed route/chart preferences. Use data-supported ranges and preserve exact brush/date alternatives.

3. Show units, gaps and evidence limits without interpolating missing data as actual zero. Preserve prediction confidence/fallback semantics.

4. Use one primary recalculation entry per task with job status; device Alerts shares named filters and deep-link context with global Inbox.

## Compatibility and failure handling

No existing domain behavior or authorization gate is relaxed. Preserve canonical IDs, enums, request fields and server validation; display names are presentation only. Retain unsaved input, partial successful data and known asynchronous job state during recoverable failures. Any new server capability discovered during implementation must have its request, response, error and compatibility contract specified before use.

## Delivery and verification

Implement shared dependencies first, then focused workflows. Reuse existing component conventions during migration and avoid parallel catch-all settings services. Test normal, loading, empty, filtered-empty, error and stale states at relevant boundaries; use 360/390/768/1024/1280/1440 px and 200% text zoom for layouts. Check keyboard, focus, long names and busy/duplicate-submit states. Cover USER/SUPPORT/ADMIN visibility with server authorization intact. Populate audit code-only states with local fixtures; keep generated evidence and secrets out of commits.

Run frontend typecheck, test and build; run OpenSpec validation and structure check. Update relevant current API/flow documentation only when behavior is implemented; the source audit remains a target requirements document. 

