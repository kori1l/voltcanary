# Design: One repair coordinator and explicit stopping conditions

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


Architecture amendment,2026-10-26: [assistant-v2 orchestration](../../../docs/target/ai-assistant-orchestration.md)
and the [new program](../README.md#ai-assistant-orchestration-program) own sequencing.
This change integrates typed recovery/UX, not a second budget service. A2 owns the
only durable reservations and model-policy decisions. A5 uses offline integration;
A6 supplies shared final live evidence. Existing local implementation was resumed
explicitly; this planning amendment authorizes neither runtime changes nor new trials.

## Deferred recovery and first-pass measurement — 2026-10-28

Implement resilience at the end of A5, after core skills/context/output/workflows
and their first-pass checkpoint. Provide an explicit isolated diagnostic profile:
one candidate per logical stage, automatic format/preparation/outcome repairs off,
model judges off, no transport retry or hidden model switch. Required planner,
step and context acquisition calls remain visible and counted by A2. Basic
schema/access/confirmation guards are never disabled to make a run look better.

For later recovery-enabled runs retain the immutable initial candidate disposition
and correlate all corrections to it. Report first-pass task success, recovery
success among initial failures, final task success and complete calls/tokens/cost
separately. A retry is not a new independent trial. Repeating a failed case after
a prompt change creates a new versioned experiment; it cannot replace old scores.
Optional evaluator calls are separately identified and charged; offline code
grading is preferred for machine-checkable effects. Failed first candidates stay
failed in first-pass metrics even when recovered, and failures/unreached steps
remain in the predeclared denominators.

## Existing seams and actual failure
`ActionPlanner.plan` can perform a second model call for malformed composition/
clarification. `CreationTaskPlanning.prepare` can call that planner up to three
times after `DashboardPreparationRejected`. The resulting total can exceed a
reader's assumed two repairs. `DashboardCompositionCompiler` also folds several
HTTP/preparation failures into wording asking the user to choose technical fields.
Replace dashboard-specific nested ownership, retaining unrelated legacy/device
behavior and adapter/provider compatibility.

## Typed result taxonomy
Use bounded `PlanningDiagnostic` values: stage, category, code, operation/goal/
widget key, safe field path, supported choices and optional retriable flag.
Separate at least:
- MODEL_FORMAT / OPERATION_INVALID: malformed/unknown/contradictory model output.
- CAPABILITY_MISMATCH / REFERENCE_STALE: genuine type/view or renewed-ref issue.
- OUTCOME_MISSING / UNEXPECTED_CHANGE / NO_EFFECT / LAYOUT_CONFLICT: verifier faults.
- USER_CHOICE_REQUIRED: two valid targets, missing explicit selection or unclear goal.
- DATA_EMPTY / DATA_STALE: evidence availability, generally a legitimate preview.
- ACCESS_DENIED / RESOURCE_GONE: authority/resource changes, never repair by bypass.
- DEPENDENCY_UNAVAILABLE / PROVIDER_UNAVAILABLE / MODEL_OUTPUT_LIMIT: infrastructure
  and output-capacity problems, not requests for another dashboard name.

Map HTTP400/422 validation bodies, normal prepare NEEDS_INPUT diagnostics and
provider exceptions into this taxonomy in adapters. Redact and bound upstream
text; domain policy does not parse Russian error strings or raw exception bodies.
If owning preparation lacks needed safe structured codes, add them compatibly
alongside existing messages. Never swallow401/403/404/5xx as empty catalogues.

## Policy and global budget
Consume A2's versioned routing policy and durable turn/parent-plan budget at every
provider boundary. PINNED preserves the chosen configuration; explicit AUTO may
select a qualified allowlisted candidate with a recorded reason and remaining
budget. Call limits are deployment policy, not an extra hard-coded local default.
Planner, context continuations, known-field/format/outcome repairs and optional
judges all consume the same reservations, including failed/uncertain attempts.
Credentials remain transient in the existing provider service.

Enforce input/output-token bounds supported by the selected model, operation/
context byte limits and the call ceiling before the next call. Record actual
usage/cost if available. Do not reintroduce the removed short response deadline:
a slow in-flight call keeps the current thinking/minimize behavior and does not
launch a speculative second call. Explicit cancellation marks the turn cancelled;
late results cannot replace a newer accepted state. Cancellation is separate from
legacy domain UNKNOWN execution/reconciliation.

Recovery order:
1. Validate typed input, current authority and capability snapshot.
2. Apply a deterministic normalization only when the meaning is uniquely preserved
   and the rule is explicit/tested (e.g. allowed formatting or known exact ref renewal).
   Do not guess a different source, metric, unit, text-to-number mapping or chart role.
3. For correctable model/candidate faults, send the policy-selected model the
   original turn, known facts, pending goals, unchanged base, rejected operations,
   bounded current choices and failed check codes. Recompile from the accepted
   base, not a partially mutated failed candidate, then verify again.
4. For genuine missing user meaning, return one structured question naming actual
   choices, with all already-known facts retained. Known-field questions are
   diagnosed internally; they cannot repeatedly reach the user as new questions.
5. Stop on exhausted budget, repeated identical normalized candidate/failure,
   access failure, unsupported capability or unavailable dependency. Expose the
   appropriate honest outcome and preserve pending work/accepted review.

Provider/infrastructure failure is not automatically followed by an unapproved
model or a schema repair prompt. Any allowed retry/escalation uses A2's policy and
remaining budget. A later explicit retry is a new turn/request with preserved state
and parent-plan ceiling; duplicate delivery reuses the persisted outcome. Missing data is not repaired into
fresh data. A change of requested semantics needs user choice, not silent fallback.

## Persistence and response semantics
Use the conversation and observable trace contracts from
`persist-ai-assistant-conversations` and `trace-ai-assistant-turns`, plus A3 typed
plan/receipt facts. This stage contributes recovery diagnostics through
that recorder; conversation storage, trace APIs and trace inspection are not rebuilt.

Persist attempt state/failure categories with the durable turn record, and
correlate each actual provider invocation with existing `AiAgentModelRunRecorder`
and `ai_plan_audit` where applicable. Use turnId when a plan does not yet exist.
If extra columns are needed, add the next migration; do not update old migrations
or overload execution action status for planning failures.

READY means a verified candidate. NEEDS_INPUT means a real missing user choice;
never create an empty NEEDS_INPUT plan merely because retries were exhausted.
Use the established safe error response with additive task/turn/retry metadata
for exhausted planning/provider failure and preserve documented legacy status
semantics (e.g.502 for provider planning failure); exact new codes are contracted
before frontend rollout. Task recovery reports the persisted failed turn without
another model call. An outcome sidecar communicates verified no-op separately.

Frontend keeps the previous preview/history, shows a concise recoverable failure
and retry action, and does not auto-answer or start clarification-default timers
for technical failures. During pending replacement the old create confirmation
stays invalid; after failure the operator can explicitly select/review the previous
version. Progress displays only stages actually emitted/known by the server;
do not invent a validator stage or expose private reasoning.

## Observability and rollback
Record task/turn/base-review/candidate IDs, provider/model/prompt/schema versions,
attempt number, safe stage/code, latency, available usage, verified diff summary,
final disposition and whether a model call really occurred. Do not log raw user
JWTs, API/source credentials, unrestricted context, raw upstream errors or CoT.
Evaluate wrong-tool/argument/outcome faults through traces of actual actions and
results, not requests for the model's hidden reasoning.

Roll out in this order: additive migrations/readers -> opt-in new planner ->
synthetic/API/browser and real-model repeated acceptance -> default enable. Before
enable, retain original Russian conversation trials, five repeats of each critical
sequence, complete corpus metrics, exact execution-draft comparison, cancellation/
restart/concurrency and two-account tests. A failing critical case blocks enable.
Rollback disables new generation and preserves readers/execution of accepted
reviews, state, receipts and audit; no destructive table rollback or automatic
replanning of existing tasks.

## Verification and basis
Test the exact total of provider calls under combined format/preparation/outcome
failures, repeated-error stopping, pinned model, no budget resets, failure
classification, known-name reuse, pending-goal retention and no mutation before
confirmation. Browser-test retry, reload, old-preview availability and no default
timer on technical failure. Use real-provider repeated evals for final quality.
[v0's engineering account](https://vercel.com/blog/how-we-made-v0-an-effective-coding-agent)
motivates layered deterministic/model repair; the budget and Java coordinator here
are project choices. [Anthropic tool engineering](https://www.anthropic.com/research/writing-tools-for-agents)
motivates outcome-driven iteration on tool errors and realistic tasks.

## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
