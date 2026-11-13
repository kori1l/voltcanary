## 1. Operation contract and compiler
- [x] 1.1 Define versioned Java operation types, JSON Schema parity, limits, conflict rules and operation-local references; document all writable fields.
- [x] 1.2 Implement scoped target/data resolution, unique semantic reuse and stable per-turn new widget keys without HTTP dependencies in domain code.
- [x] 1.3 Implement role, placement, size, settings, title, data, period and removal lowering through existing delta/preparation seams.
- [x] 1.4 Generate the allowed-change mask and expected outcomes independently of model-supplied preservation claims; define necessary reflow versus forbidden repacking.
- [x] 1.5 Route exact restoration to authorized saved history and reject mixed restore/edit envelopes.

## 2. Planning integration and compatibility
- [x] 2.1 Add versioned creation/refinement prompts and bounded current-review/data projections using the current provider adapters.
- [x] 2.2 Integrate compilation with durable task state and pending-change IDs; consolidate duplicate delta/normalization rules.
- [x] 2.3 Preserve legacy templates/compositions/execution receipts and continuation adaptation; add opt-in rollout and rollback compatibility.
- [x] 2.4 Align AI/dashboard API and architecture docs, operation examples and user-visible size/reflow semantics.

## 3. Verification and rollout gates
- [x] 3.1 Test operation bounds/conflicts, references, semantic reuse, all preservation masks, typed settings resets and breakpoint geometry.
- [x] 3.2 Test new/legacy prepare-digest and confirmed execution equivalence, current authorization and exact restore compatibility.
- [x] 3.3 Run repeated real-model compound tram cases with the evaluation harness; keep the new default disabled until outcome-verification and final repair gates pass.
- [x] 3.4 Run affected Java/frontend checks, typecheck/build, structure and strict OpenSpec validation; record measured limitations rather than silent fallback.

## Evidence and remaining release gates

Implemented opt-in; default remains disabled. See docs/current/flows/ai-creation-verification.md
and ignored artifacts/dashboard-operations-report.md. Frozen real-model quality FAILED:
26/50 dialogues,75/104 turns. Matched stage1 cohort10/40 ->21/40; stage3 cohort11/16 ->12/16.
Stages5/6 own goal outcome verification/recovery and final release acceptance.
Java121 AI/93 dashboard/10 shared, frontend389/typecheck/build, harness35 passed;
later schema/name fixes passed17/30 focused tests. Structure and strict OpenSpec
validation passed. Confirmed real-model execution9/9; rollback recovered the exact
review/draft and replayed the same receipt with flagfalse. Synthetic browser draft
archived and device decommissioned. Visual one-row clipping remains acceptance work.
Post-corpus name probe6/7: durable rename passes, subsequent header-only preservation
fails; scores were not retrospectively revised. No final default-enable claim.
