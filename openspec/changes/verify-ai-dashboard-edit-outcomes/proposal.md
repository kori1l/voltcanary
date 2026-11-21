# Change: Verify dashboard edit outcomes before accepting a review

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Use the [current implementation order](../README.md#ai-first-execution-priority) for configurable scope. Live evaluation follows the owning change’s authorization and qualification gates.


Current status (2026-10-28): V owns outcome verification (0/14 tasks checked; partial structural checks exist). Implement the remaining V work at the end of A5, with final evidence in A6. This is not a separate stage before core A5.

## Why
A definition can be technically valid yet fail the user's request or modify an
unrelated panel. Current preparation validates configuration and digests; it is
not a full check that the requested conversation outcome was achieved. Acceptance
must compare the candidate with the actual base and retained requested work.

## What Changes
- Add an application outcome verifier with code-based checks for operation results,
  pending-goal coverage, allowed field differences, identity/order and responsive
  geometry. Reject a candidate atomically before it becomes READY.
- Distinguish verified already-satisfied work from unsupported/empty/no-op output.
- Generate truthful change summaries from verified differences, retaining the
  last valid review when a candidate fails.
- Add shared-preview browser/readability acceptance at normal and compact sizes,
  alongside explicit real-data/no-data/stale behavior.

## Dependencies

2026-11-11: [AI-first/library migration](../README.md#ai-first-execution-priority)
integrates applicable verifier predicates into the one graph runtime. V retains
independent outcome/intent/preservation authority and final A5/A6 gates below;
the graph neither replaces the verifier with its own success flag nor requires
full V archival before basic graph implementation can proceed.
2026-10-28 sequencing amendment: implement V at the end of A5, after core skills,
context/output/workflow development and a no-repair/no-model-judge first-pass
checkpoint. The paragraph below describes ownership seams, not a requirement to
finish V before core A5 work. Final live acceptance remains in A6.
Amended2026-10-26: use the [orchestration program](../README.md#ai-assistant-orchestration-program).
Reuse completed C1/C2 history and traces and the existing compiler/capability baseline.
A1/A3/A4 supply the new contracts, durable plan/goal/resource state and bounded
context seams. Implement verification and offline checks in A5's final integration
package. Full archival/live acceptance is not an A5 prerequisite: A6 supplies
shared real-model/browser/execution evidence for the remaining tasks. C3 behavior
must be covered in A5/A6; closing old phrase-matching acceptance first would be a cycle.
Emit diagnostics through the shared trace; do not add another trace store.

Requires baseline evals, durable state, capability resolution and semantic compiler.
Provides the postcondition portion of old section12.2 and release checks from12.4.
Its typed diagnostics feed `repair-ai-dashboard-planning-failures`.

## Ownership and impact
ai-agent-service domain/application code verifies intent/candidate differences;
dashboard-service remains the definition/layout/data authority. Frontend reuses
DashboardDefinitionPreview/Canvas/WidgetPanel and existing history/confirmation.
There is no independent mock renderer or model-only approval of structural checks.

## Out of scope
No claim that code can prove unrestricted natural-language interpretation correct,
no fabricated telemetry to make previews look complete, no mandatory screenshot
model call for every turn, and no visual-judge veto over valid missing-data states.

## Acceptance
Wrong-widget edits, silent no-ops, omitted known mandatory goals and collateral
binding/settings changes cannot become READY. Necessary layout reflow is accepted;
presentation-only edits cannot repack the grid. Preview and confirmed creation
retain exactly the accepted configuration. Visual quality is verified separately
from shape/schema correctness with repeatable browser evidence.
