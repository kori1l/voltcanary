# Design: Verify outcome and preservation before READY

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


Architecture amendment,2026-10-26: this change remains the sole owner of dashboard
postconditions, summaries and verified no-op. Integrate with assistant-v2 skill
proposals, durable goals and A2's shared budget as described in
[target orchestration](../../../docs/target/ai-assistant-orchestration.md).
A5 consumes implemented offline checks; A6 supplies shared live evidence, so full
archival is not a prerequisite to A5. No duplicate verifier or extra retry loop.

## Verification inputs and limits
The verifier receives the exact selected base definition/digest, durable pending
goals, resolved capability snapshot, validated operations, compiler-derived
allowed-change mask and prepared candidate definition/digest. It is a pure domain
component coordinated by an application service; it does not call a provider,
browser, repository or owning-service controller from domain code.

Each interpreted request records bounded mandatory goals with goalId, source turn
and grounded user wording; operations reference the goals they fulfill. Existing
pending goals cannot disappear merely because the next model output omits them.
Coverage checks ensure every retained mandatory goal is fulfilled, explicitly
clarified or superseded by a user instruction. The operation writer cannot set
its own check results or decide which existing pending goals to delete.

There is an important limit: goal extraction itself uses language interpretation
and may miss a user intention. Code can prove structural outcomes against recorded
goals and operations, not prove perfect understanding of arbitrary prose. Measure
interpretation completeness using independent reference outcomes in the eval
corpus, held-out cases and human review. Any optional model grader is calibrated
against those references and never replaces binding/identity/security checks.

## Postconditions

### Timing and independent assessment — amendment 2026-10-28

V is implemented at the end of A5 after core skill/context/output development
and its first-pass checkpoint. It is not added to the diagnostic first-pass
profile as an extra model call. Offline reference assertions may grade results
after the run; runtime schema/access/confirmation guards remain active throughout.

Writer-selected goals/masks can repeat a wrong interpretation. Assess explicit
typed facts in code; for unresolved semantic target selection, a read-only
application assessment may use original wording, required history/question,
authorized base/widget inventory and actual diff. Writer explanations or
self-awarded success are not authority. Return bounded criteria, source/target
evidence and satisfied/rejected/uncertain status, not private reasoning.
Unresolved mandatory checks cannot qualify a final READY candidate.

The pure domain verifier stays provider-free. Any model assessment is routed and
charged by A2; a separate call to the PINNED model is not proof of statistical
independence. Never silently upgrade models or override deterministic checks.
Calibrate against independent human-reviewed labels/held-out effects and measure
false accept/reject, including writer/assessor agreement on wrong targets.
Assessment rejection never changes a first-pass failure into a first-pass success.
Produce `VerificationReport` with bounded check IDs, safe diagnostics, diff and
APPLIED/ALREADY_SATISFIED/REJECTED result. Verify:
- Every targeted widget exists/removal is exact; new widgets correspond only to
  missing requested semantic roles and stable turn keys.
- Current versus history role, device/source/field/metric, requested view, name,
  period, presentation and size satisfy the interpreted mandatory goals.
- Every candidate field difference lies within the compiler's allowed mask.
  Canonical comparison ignores JSON object key order, never meaningful array
  order, null/default semantics or changes to unrelated payload/query fields.
- Untargeted widget IDs/type/binding/query/presentation/payload remain equal.
- Partial placement preserves other widgets' relative order at all breakpoints;
  grid bounds, collisions, minimum sizes and permitted packing reflow are checked.
  Settings/title-only edits allow no layout changes. Downstream row movement
  necessary to insert a top group is not falsely treated as unrelated corruption.
- Dashboard root fields are preserved except explicit operations and confirmed
  task fact updates that are represented as pending goals.
- The returned prepared digest matches the candidate actually being accepted.

Full compound turns are atomic: one failed mandatory check rejects the candidate
and preserves the previous review. Do not accept only the easy operations while
claiming the whole request completed. Explicit user-approved splitting can create
separate turns with clear remaining pending work.

## No-op and truthful response contract
If a requested property already has the desired value in the base, prove that goal
directly and return ALREADY_SATISFIED with a clear explanation. An empty operation
list, repeated old definition or a change unrelated to the requested goal is not
proof of satisfaction. Retained failed work prevents an unverified no-op result.

Use an additive `refinementOutcome` sidecar in PlanResponse (type, verified changes,
baseReviewId, failed/pending goal IDs) tied to the persisted turn. On an already-
satisfied request return the existing authorized review with its original ID,
version, explanation and digest plus that turn outcome; do not rewrite immutable
plan text or create a duplicate executable review. Frontend displays the sidecar
as the turn result and keeps the existing preview. Legacy response fields retain
their meanings; do not overload NEEDS_INPUT to mean internal validation failure.

Only a verified candidate is saved as a new READY review and becomes
lastAcceptedReviewId. The summary is generated from checked differences, including
row quantization and necessary reflow. A failed candidate yields safe diagnostics
to the recovery coordinator; the user continues to see the accepted review plus
honest pending/error status. Confirmation belongs only to the accepted plan and
is invalidated while replacement work is in flight.

## Shared preview and visual quality
Reuse `DashboardDefinitionPreview`, `DashboardCanvas`, `DashboardWidgetPanel` and
ordinary authorized query APIs. No client-side alternate layout generation. Real
data may change between snapshots; equality of reviewed/saved configuration does
not promise identical future telemetry values. Keep asOf/freshness evidence explicit.

Browser acceptance uses stable synthetic numeric/boolean/text/empty/stale fixtures
at desktop and narrow mobile widths, plus real HSL integration smoke. Check:
- Header hiding on all types, editor drag controls and accessible names retained.
- Map details/notices obey settings while attribution and genuine errors remain.
- One-row current cards show the main value and concise stale/unavailable state
  without clipping; full provenance is available through panel information.
- Axes/labels/markers retain proportions and boolean history shows observed states
  and unknown gaps. Supported minimum chart size remains enforced.
- Intentional list/table scrolling is not confused with clipped main-value text.
  Use measured relevant-element bounds and screenshots, not a blanket rejection
  of any scrollHeight greater than clientHeight.

Keep deterministic style/layout regressions in frontend tests. Human-reviewed
screenshots or an optional calibrated visual grader assess aesthetics/readability;
they are separate from runtime structural acceptance and cannot fabricate success.

## Integration, diagnostics and rollout
Add the verifier between ordinary prepare and `PlanStore.save`. Expose typed
check failures through safe bounded codes such as OUTCOME_MISSING,
UNEXPECTED_CHANGE, NO_EFFECT and LAYOUT_CONFLICT, without leaking raw upstream
responses. Reuse check IDs in audit and evaluation reports. The next change owns
retry policy; this change does not add another retry loop.

Compare exact reviewed versus created private draft in ordinary execution smoke;
retain current prepareDigest/payloadDigest checks and receipts. Current access
revocation remains an error even if the previous configuration passed checks.
Do not default-enable semantic generation before these invariants pass. Retain
legacy execution semantics; apply checks to newly generated candidate reviews.

## Verification basis
[Anthropic evaluation guidance](https://www.anthropic.com/research/demystifying-evals-for-ai-agents)
supports deterministic outcome graders where possible and independent human/model
review for nuance. [tldraw's spatial harness](https://tldraw.dev/blog/harnessing-the-agents)
shows structured and visual context plus layout diagnostics as distinct concerns.

## Explicit VERIFIED integration (2026-10-28)

The opt-in VERIFIED profile runs canonical assessmentResponse before review persistence using the same A2 turn/plan ledger and PINNED model. Writer goals/masks/prose are excluded from its wire projection; original dialogue, base inventory, capabilities and compiled candidate remain. Source quotes, candidate digest and widget evidence are checked. Rejection/uncertainty prevents READY; assessment has no format retry. Existing bounded domain repair may use its A2 allowance for concrete rejection. Late/cancelled results and draft-version changes are rejected before save. Structural checks remain mandatory. FIRST_PASS_DIAGNOSTIC has no assessment calls; STANDARD defaults are unchanged. Open designs have no fixed widget bundle. This partial integration does not complete calibrated false acceptance/rejection, verified no-op/sidecar, full UI/browser or A6 acceptance.

## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
