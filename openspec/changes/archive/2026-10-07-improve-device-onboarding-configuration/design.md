# Improve device inventory, onboarding, imports and configuration: design

## Context and scope

Audit coverage: F07, F08, F09, F10, F16, F17. The audit separates browser observations from code-only states; implementation must verify both rather than assuming a populated state already passed.

## Dependencies and ownership

- Depends on [establish-console-design-system](../2026-10-07-establish-console-design-system/proposal.md).
- Depends on [fix-console-data-state-semantics](../2026-10-07-fix-console-data-state-semantics/proposal.md).

Owners: frontend/src/pages/DevicesPage.tsx, device configuration and simulation feature modules, frontend/src/devices/ and frontend/src/simulation/. Keep route pages as composition shells and extract touched workflows into focused components/hooks/model helpers. Use typed domain clients and preserve auth/error semantics.

## Decisions

1. Offer compact table and existing location/card views using canonical IDs behind named selectors. Distinguish registry state, telemetry freshness and risk.

2. Model wizard steps and created-resource progress explicitly. On a partial setup failure resume the unfinished step against the existing resource rather than blindly creating a duplicate; do not automatically delete user resources.

3. Preview device/source CSV locally against supported headers before existing import calls; keep server validation and imported/rejected counts authoritative.

4. Provide typed known analytics fields with Advanced JSON for unsupported keys and round-trip preservation. Use supported metric catalogs without pretending nonexistent metrics or connector capabilities exist.

5. Separate simulator draft, saved config and active run; presets remain primary and YAML advanced.

## Compatibility and failure handling

No existing domain behavior or authorization gate is relaxed. Preserve canonical IDs, enums, request fields and server validation; display names are presentation only. Retain unsaved input, partial successful data and known asynchronous job state during recoverable failures. Any new server capability discovered during implementation must have its request, response, error and compatibility contract specified before use.

## Delivery and verification

Implement shared dependencies first, then focused workflows. Reuse existing component conventions during migration and avoid parallel catch-all settings services. Test normal, loading, empty, filtered-empty, error and stale states at relevant boundaries; use 360/390/768/1024/1280/1440 px and 200% text zoom for layouts. Check keyboard, focus, long names and busy/duplicate-submit states. Cover USER/SUPPORT/ADMIN visibility with server authorization intact. Populate audit code-only states with local fixtures; keep generated evidence and secrets out of commits.

Run frontend typecheck, test and build; run OpenSpec validation and structure check. Update relevant current API/flow documentation only when behavior is implemented; the source audit remains a target requirements document. 

