# Establish a responsive console design system: design

## Context and scope

Audit coverage: UI-01, UI-04, UI-05, UI-06, UI-07, UI-08, UI-09, UI-10, UI-11, UI-12, UI-13, UI-14, UI-15. The audit separates browser observations from code-only states; implementation must verify both rather than assuming a populated state already passed.

## Dependencies and ownership

- Shared foundation; other UI changes consume its tokens, controls and shell primitives.

Owners: frontend/src/app/, frontend/src/components/, focused feature components across the console. Keep route pages as composition shells and extract touched workflows into focused components/hooks/model helpers. Use typed domain clients and preserve auth/error semantics.

## Decisions

1. Use shared tokens and controls with scoped styles; avoid broad input rules that stretch checkboxes or time fields. Preserve the yellow brand mark and use neutral surfaces with one action accent.

2. Choose breakpoints from available content width, not route-specific hard-coded minimum widths. Keep scrolling inside wide tables/timelines, never the entire document.

3. Build shared page headers, exact active navigation, an opaque dialog/drawer and a confirmation-with-reason component. Route shells compose them; business checks stay in feature modules.

4. Prepare desktop/mobile reference prototypes for Devices, Overview/Telemetry, Alerts, Maintenance and ML Training with normal/loading/empty/error/stale states before broad adoption. No global language switch is inferred from the Russian audit.

## Compatibility and failure handling

No existing domain behavior or authorization gate is relaxed. Preserve canonical IDs, enums, request fields and server validation; display names are presentation only. Retain unsaved input, partial successful data and known asynchronous job state during recoverable failures. Any new server capability discovered during implementation must have its request, response, error and compatibility contract specified before use.

## Delivery and verification

Implement shared dependencies first, then focused workflows. Reuse existing component conventions during migration and avoid parallel catch-all settings services. Test normal, loading, empty, filtered-empty, error and stale states at relevant boundaries; use 360/390/768/1024/1280/1440 px and 200% text zoom for layouts. Check keyboard, focus, long names and busy/duplicate-submit states. Cover USER/SUPPORT/ADMIN visibility with server authorization intact. Populate audit code-only states with local fixtures; keep generated evidence and secrets out of commits.

Run frontend typecheck, test and build; run OpenSpec validation and structure check. Update relevant current API/flow documentation only when behavior is implemented; the source audit remains a target requirements document. 
