# Improve ML setup, data preparation, training and operations: design

## Context and scope

Audit coverage: F26, F27, F28, F29, F30, F31, F32, F33, F34. The audit separates browser observations from code-only states; implementation must verify both rather than assuming a populated state already passed.

## Dependencies and ownership

- Depends on [establish-console-design-system](../2026-10-07-establish-console-design-system/proposal.md).
- Depends on [fix-console-data-state-semantics](../2026-10-07-fix-console-data-state-semantics/proposal.md).

Owners: frontend/src/features/ml-settings/, frontend/src/features/ml-datasets/, focused ML import/label/model/policy modules, typed analytics clients. Keep route pages as composition shells and extract touched workflows into focused components/hooks/model helpers. Use typed domain clients and preserve auth/error semantics.

## Decisions

1. Retain ml-console-workflows, ml-dataset-lifecycle, ml-feature-platform and ml-deployment-operations contracts; this change improves their presentation, not training semantics.

2. Use a collapsible actionable pipeline, named entity scope selectors and explicit account/device inheritance. Do not present confidence thresholds as measured model accuracy.

3. Workbench metrics come exclusively from backend preview. Changing scope/metrics/range/family/horizon/split/features/gates invalidates preview; guard out-of-order responses and creation until fresh.

4. Build bounded local file preview/column mapping for existing CSV/JSONL import schemas; raw JSON remains advanced and server validation/lineage remains authoritative.

5. Keep dataset, labels, snapshot and training family/horizon compatibility explicit; lifecycle actions reuse backend impact previews, confirmations and existing reasons.

6. Show monitoring ratios only with a valid sample set and window. Model approval/deployment follows actual gates and supported policy payloads; UI cannot waive backend gates.

## Compatibility and failure handling

No existing domain behavior or authorization gate is relaxed. Preserve canonical IDs, enums, request fields and server validation; display names are presentation only. Retain unsaved input, partial successful data and known asynchronous job state during recoverable failures. Any new server capability discovered during implementation must have its request, response, error and compatibility contract specified before use.

## Delivery and verification

Implement shared dependencies first, then focused workflows. Reuse existing component conventions during migration and avoid parallel catch-all settings services. Test normal, loading, empty, filtered-empty, error and stale states at relevant boundaries; use 360/390/768/1024/1280/1440 px and 200% text zoom for layouts. Check keyboard, focus, long names and busy/duplicate-submit states. Cover USER/SUPPORT/ADMIN visibility with server authorization intact. Populate audit code-only states with local fixtures; keep generated evidence and secrets out of commits.

Run frontend typecheck, test and build; run OpenSpec validation and structure check. Update relevant current API/flow documentation only when behavior is implemented; the source audit remains a target requirements document. Run ML production Docker smoke for runtime, lifecycle, artifact, feature-contract or deployment changes; lack of MinIO must be reported as unverified rather than passing.

