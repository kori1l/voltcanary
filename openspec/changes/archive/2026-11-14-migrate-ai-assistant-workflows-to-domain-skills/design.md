# Design: Migrate assistant workflows to reviewed domain skills

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


## Workflow parity matrix

The current generation route is assistant-v2. The inventory covers
`AgentTool` and the separate refinement intent in `PrimarySkillPackages`.
The shared executor still reads retained reviews and dispatches their original
tool payloads. Existing API compatibility does not establish new NL parity.

| Tool or workflow | Current path and offline evidence | Remaining parity |
| --- | --- | --- |
| CREATE_DEVICE | Catalogue-backed v2 preparation resolves location references to owner IDs and enforces the owner's name and optional text constraints before review. `PrimaryDeviceProposalTest`, `DeviceCreationSchemaTest`, `DeviceSetupExecutorTest`. | Verify catalogue types, locations and optional fields together through the v2 writer and owner readback. |
| CREATE_DATA_SOURCE, HSL | Separate v2 review for active VEHICLE and installed HSL. `DeviceExecutorBoundaryTest` exercises the connector receipt. | Verify the full dependent chain with actual owner source and dashboard field readback. |
| CREATE_DATA_SOURCE, generic | Retained provider-less action uses the ordinary source endpoint. New v2 command is HSL-only. | Qualify each supported connector workflow using its own owner contract. |
| CREATE_DEVICE, legacy composite HSL | Retained reviewed payload uses the existing device setup executor. `DeviceSetupExecutorTest` covers waiting and unavailable integration. | Exercise retained composite confirmation and migration alongside the new separate workflow. |
| CREATE_DASHBOARD | Semantic operations over scoped device/source choices. `DashboardCapabilityContextTest` covers retention of an authorized source before its first field appears and rejects a source from another device. `DashboardSkillContractTest`, `PrimaryWorkflowContextTest`, `DashboardExecutorBoundaryTest` cover operations, context and execution. | Verify confirmed created-device and source continuity, reload, explicit device switching and no-data/stale states through actual owners. |
| REFINE_DASHBOARD_REVIEW | Separate v2 intent requires an unexecuted create review. `PrimaryWorkflowContextTest` covers base selection. | Complete targeted edit, preservation, history and restore acceptance. |
| UPDATE_DASHBOARD_DRAFT | Separate v2 intent prepares a revision-bound save. `DashboardExecutorBoundaryTest` covers same ID and conflict. `DashboardReviewHistoryTest` exercises saved ancestry and verifies the exact historical definition in the versioned save request and receipt. | Qualify confirmation, persisted readback and undo through the running dashboard service. |
| CREATE_NATIVE_TABLE | Default-off owner workflow. `NativeTableWorkflowTest` covers authority, reserve and preparation. | Broader configurable workflows and model qualification belong to G7. |
| CREATE_SIMULATOR_CONFIG | Registered v2 command. Shared executor writes simulator config for explicit device/source. | Verify scoped preparation and readback with current simulator/source contracts. |
| VALIDATE_SIMULATOR_YAML | Registered v2 command and simulator validation endpoint. `RulesPlanningTest` covers missing and explicit YAML in the legacy rules planner. | Verify equivalent v2 input and read-only execution. |
| CREATE_DEMO_DEVICE | Registered v2 command. Shared executor supports demo setup and optional simulation start. | Verify compound confirmation, source/config/start outcomes and partial failure. |
| START_SIMULATION | Registered v2 command. Shared executor starts a configured simulation. | Verify authorized v2 selection and status readback. |
| STOP_SIMULATION | Registered v2 command. Shared executor stops a simulation. | Verify authorized v2 selection and status readback. |
| CREATE_ALERT_RULE | Registered v2 command and shared owner executor. | Verify scoped reviewed parameters and saved rule equality. |
| UPDATE_ALERT_RULE | Registered v2 command and shared owner executor. | Verify actual rule identity, preserved settings and readback. |
| TEST_ALERT_RULE | Registered v2 command and shared owner test operation. | Verify authorized interval and read-only outcome. |
| EXPLAIN_ALERT | Registered v2 command and shared owner evidence read. | Verify scope, evidence and absent-data responses. |
| OPEN_TELEMETRY_WINDOW | Registered v2 command and shared navigation action. | Verify device/metric/interval grounding and frontend navigation. |

`PrimarySkillManifestTest` checks every registered skill contract.
`IntentWorkflowScenarioTest` and `LangGraphAgentIntegrationTest` check durable
workflow and restart boundaries with controlled fixtures. Full owner and
provider qualification remains part of the corresponding acceptance tasks.

## Contract hardening after native diagnosis (2026-11-02)

Represent planner decisions as disjoint finite variants rather than silently
dropping their conditional field constraints in provider schemas. Narrow initial
versus existing-plan decisions from typed plan state, not user phrase matching.
Constrain data replacement with the owner snapshot's widget type, visualization
and authorized choice compatibility. Corrective diagnostics identify the invalid
pair and offered replacements; they do not change the user's selection.

The creation wire must explicitly choose the whole dashboard period independently
of chart periods. Lower that wire choice into the existing canonical set_period
operation before domain validation; do not infer global intent from one chart's
period or change saved review payloads. Keep canonical readers and confirmation,
revision, receipts and shared budgets unchanged. Prompt responsibilities and
visibility groups must be general domain rules, not frozen-case answers. Retain
failed-call usage in trace diagnostics. Verify these changes offline before any
new separately authorized live qualification; no model training is involved.

## Native dashboard tools (2026-10-30)

Creation, refinement of an unexecuted review and updates to an existing draft
already have separate typed skill guides and operation contracts. Keep them
separate. In NATIVE_TOOLS mode expose exactly one corresponding Spring AI tool:
prepare_dashboard_creation, prepare_dashboard_refinement or
prepare_dashboard_update. Its arguments derive from the selected registered
step-response schema, including authorized dynamic operation variants. PREPARED,
QUESTION and NEED_CONTEXT remain canonical outcomes, so missing context returns
through the bounded durable loader loop rather than an SDK-owned tool loop.
Planners, independent assessors and other skills keep assistant_proposal.

These are review preparation tools. Widget placement, resizing, data replacement
and presentation changes remain semantic operations in one atomic proposal,
not separate immediate mutations or extra provider calls per widget. Disable
automatic tool execution, accept exactly one call to the selected name and
normalize strict arguments back through existing canonical/domain validation.
Existing authorization, revisions, confirmation, receipts and call/cost limits
remain authoritative. Activate explicitly with supportsTools=true and
responseFormat=tool; never infer capabilities from a model name or downgrade
after failure. Offline HTTP fixtures verify provider wiring, not model quality.

## Lossless catalogue and edit-context compression (2026-10-30)

Embeddings identify relevant parameters but do not encode executable contracts.
Compress the wire projection through exact shared references: retain every widget
overview and its compatibility, binding, units and limits; intern identical owner
setting contracts, exact JSON schemas and legacy hints once. Each widget's setting
keys reference those dictionary entries. Retrieval results reference the same
entries. A search miss therefore cannot hide an available capability, even with
embeddings disabled. Canonical loaded context and server validation remain full.
Identical catalogue/dictionary bundles across repeated workflow loads reference
the first exact bundle by JSON Pointer. Per-load data choices and different
catalogue/default/schema bundles remain separate.

Replace duplicated writer change-context arrays with explicit references to the
exact original goals, pending question, resolved inputs and widget inventory.
Do not drop dialogue, accepted definitions or schema constraints. Keep assessor
inputs independent from writer goals. Record projection byte reduction separately
from semantic quality. Preserve the conservative UTF-8 admission estimate and all
existing limits; establish the saved selected-vehicle regression with offline
replay, not another paid prompt-tuning campaign.

## Widget capability retrieval amendment (2026-10-29)

The dashboard owner remains the only authority for widget and setting contracts.
Expose descriptive purpose and generated settings schema from those same
contracts; preserve them across the AI context projection. Retrieve widget and
setting documents from this deployment-owned catalogue, never tenant telemetry,
credentials, resource names or historical test answers. Hybrid ranking combines
exact lexical evidence with optional Spring AI embeddings. Results are advisory
and never remove complete catalogue entries, confer permissions or bypass domain
validation. Add hints to dashboard writers only, not planners or assessors.

Use a bounded in-process immutable vector index keyed by complete catalogue
content plus embedding model identity. Rebuild on contract/model changes and
restart; no vector database or migration is needed for this catalogue size.
Bound document count/text, embedding batch and query length; make one embedding
batch per rebuild and one query exchange per projection, without retries. Keep
only a bounded number of catalogue snapshots and never cache user query vectors
globally. Reject nonfinite, zero, missing or mismatched vectors. On embedding
failure use explicit lexical-only diagnostics and keep all contracts. Deployment
configuration owns endpoint/model/credentials independently from account chat
providers. Embeddings default off; verification uses deterministic doubles and a
local HTTP fixture, with no real-model calls or quality claims.

The canonical design, request/response/state formats, trust boundaries and migration
rules are in [the target architecture](../../../docs/target/ai-assistant-orchestration.md). Keep schemas there authoritative
rather than copying provider-specific variants into this change.

## Specific implementation boundary

Execution amendment 2026-11-11: first follow
[AI-first/library adoption](../README.md#ai-first-execution-priority).
The following remains A5's internal quality sequence, not next-change priority.
Engineering amendment 2026-10-28: finish A5, then A6. Within A5 implement skills,
context, output contracts and core workflows first; measure first-pass quality
without automatic repair or model judges. Add V/R outcome assessment and resilience
at the end of A5, then qualify the whole pipeline in A6. Start diagnosis with the
two existing dashboard families; do not restart paid prompt-tuning loops.
The following work is proposed, not a claim about the current JSON_TEXT runtime.

### Skill package and grounded context

Give every skill versioned sections: purpose/scope, required and optional inputs,
authorized context, allowed operations, preservation rules, question/unsupported
conditions, response schema, diverse input/output examples and acceptance checks.
Use operation-specific guidance selected from the model's typed workflow/action;
never select it by regex/keywords in user prose. Build the provider projection
from the registry's schema and current capabilities, with stable package/schema
digests. Do not maintain competing hand-written provider/domain contracts.

Keep the exact current text, relevant earlier turns, exact pending question/options,
confirmed facts and accepted base. Supply a compact widget inventory with exact
keys, roles, data bindings, current presentation, dimensions and breakpoint order.
References must belong to this authorized snapshot. Remove redundant catalogue
prose, not mandatory history, to reduce context. Unknowns remain explicit.

### Output modes and decomposition

Add native strict JSON Schema or strict tool arguments when the selected provider
and model support the actual schema subset. Project discriminated outcomes into a
supported wire form and normalize back to the canonical contract; validate on the
server in every mode. Test refusal, truncation, unsupported schema and incomplete
responses. JSON_TEXT remains an explicitly declared capability mode for other
profiles, not a hidden fallback after strict-output failure. PINNED keeps model
identity. Record mode and schema versions; do not call a valid JSON result correct
merely because it parsed.

Separate grounded requested effects from operation generation and compilation.
Each requested effect links to its source message and exact widget/resource role;
changes link to effects. Use existing durable goals/bindings where sufficient;
additive contract changes require canonical schema/examples and compatibility
tests before writers. Do not add a provider call for every trivial edit: use the
smallest tested decomposition and count every required assessment in A2.
The operation writer's own target list/mask is not independent evidence that it
understood the request. V owns the later check; existing compilers own geometry.
Do not introduce a model assessor into the first-pass development profile.

### First diagnostic slice and exit

Capture safe stage/codes for context, interpretation/target selection, output
format, graph, preparation, verification and recovery. Reproduce live-12 failures
with public sanitized fixtures or test doubles, not ignored artifacts alone.
Change one relevant component at a time and keep original frozen criteria.
First exit: evaluate the same two families against independent offline assertions
for exact rename, current/history distinction, top placement and untouched order.
Use one generated candidate per logical stage, no automatic format/preparation/
outcome correction and no model judge. Necessary planner/step/context acquisition
calls still count; this is not a claim that an entire dialogue uses one HTTP call.
Schema/authorization/confirmation guards remain active, and a rejected first
candidate is scored as failed. Do not silently turn repair on to meet this gate.
Freeze a numeric first-pass target and call/token ceilings before an authorized
replay; report failure if unmet. Later V/R checks cover retained preview/reload,
wrong-candidate rejection, bounded recovery and shared-budget stopping separately.
This is a narrow regression gate, not full A5 or A6 completion. Real-model replay
requires its own explicit scope/budget; a planning prompt does not authorize it.
First implement a complete ordinary-device/HSL/edit vertical slice behind an explicit v2 writer flag. Expose a source-connection intent only for a connector with an actual authorized execution/receipt contract; fixed HSL does not imply arbitrary MQTT support. Existing stored composite HSL reviews keep their original semantics. New separate steps need ordinary domain idempotency and receipt reconciliation. Moving/resizing/settings use the existing semantic compiler and server packing, not model coordinates/full definitions. The existing verify and repair changes remain the owners of postconditions and recovery UI; no duplicate checker, retry ledger or renderer is introduced.

## Conversational continuation

Separate refinement of an unexecuted review from editing an actual created dashboard. The latter requires current draft/revision loading and reviewed UPDATE_DASHBOARD_DRAFT on the same dashboardId, with optimistic concurrency and a new confirmation after conflicts; a create-only executor cannot satisfy this scenario by creating a copy.

## Prerequisites and sequencing
A1/A2/A4 and implemented A3 runtime provide the baseline. V/R implementation is the final A5 work package, not a prerequisite for improving skills/context/core workflows. Complete A3 task1.9 integration evidence within A5. Full V/R live acceptance is supplied by A6; C3 completion is not a cyclic prerequisite.

Use the [program index](../README.md#ai-assistant-orchestration-program) for the
noncyclic order and delegated legacy criteria. Implement readers/contracts before
new writers; deployment defaults stay off until integration and qualification.
Preserve the explicitly enabled local override unless the user requests a change.

## Validation
Use offline schema, domain and compatibility checks first. Any real-model evidence
belongs to an explicitly authorized capped qualification campaign. Record all
actual calls in the existing trace and shared budget. Apply relevant existing
Java/frontend/connector checks, structure and strict OpenSpec validation; do not
run ML/load testing for this architecture unless that surface actually changes.

## Separate dashboard responsibilities (2026-10-28 follow-up)

The model-facing dashboard skills use version 2 commands with three intents:
CREATE_DASHBOARD for initial creation, REFINE_DASHBOARD_REVIEW for an unexecuted
create review, and UPDATE_DASHBOARD_DRAFT for the same versioned persisted draft
(including refinement of its pending update review). REFINE is a routing skill,
not another execution tool. All three lower through the existing operation and
composition compilers, immutable reviews, confirmations and owning receipts.

Creation has no widgetKey property, including nullable widgetKey. Exactly one
rename_dashboard is required. Creation operations use dataRef and local ids;
later operations address earlier created widgets with @id (rename_widget uses
target). After canonical/context validation the server inserts null keys and
maps the local title target to the existing internal dashboard-operations-v1
records. There is no alternate dashboard builder. New creation does not inherit
an ambient dashboard review. Explicit selection of a dashboard base requires
the appropriate edit skill; an actual device receipt is a valid creation input.

Edit skills use exact keys from the accepted inventory. Unknown identifiers,
including syntactically plausible UUIDs, are errors rather than new widgets.
Sequential local references and dynamic dataRef/key constraints are checked
before owning preparation. The compiler remains the final semantic boundary.
An AMEND retains the accepted review reference for context/preview while
invalidating the replaced step's executable review binding.

Planner inputs are resource bindings only; names, periods and widget choices
are interpreted by the selected skill from the exact dialogue. planSummary
step readers accept optional dependsOn/goalIds before the writer supplies them.
The planner guide shows replacement without depending on superseded failures.
Dashboard loader contracts are limited to device.catalogue, workflow.context
and conversation.history. Wire serialization sends the selected dynamic command
schema once, preserving message/history/question/options/facts, resolved bindings
and required review data. Versioned skill/guide digests pin in-flight policies;
old saved reviews retain their original read/confirmation/execution payloads.

This increment is offline engineering evidence. It neither restarts an exhausted
paid campaign nor establishes first-pass quality or completes V/R/A6 acceptance.

### Wire context deduplication (2026-10-28)

`primary-prompt-v1` is a wire-only projection pinned in the routing policy digest.
Canonical requests, registered loader result validation and stored reviews remain
unchanged. Loader argument schemas and question/input policies remain visible;
duplicate trust text and loader result schemas are not sent. JSON_TEXT contains
one selected response schema; native modes supply their projected schema through
the provider output contract without a second canonical schema in instructions.

An accepted dashboard action supplies its complete compiled definition once.
Its historical prepareIntent, operation mask and expected outcomes are omitted
from model input. The inventory retains keys, roles, semantic sizes and breakpoint
order; widget properties are read by joining its key to definition.widgets.id.
Workflow previousDashboardIntent is omitted only when the accepted base identity
matches. Fresh draft target/version and current capabilities remain visible.
Identical nonempty views arrays within each workflow context use a local
dashboardViewSets table and viewsRef; all per-dataRef metadata is preserved.
Exact message, dialogue, pending question/options and confirmed facts are intact.
This is structural deduplication, without semantic selection or additional calls.

### Planner reference and question ownership (2026-10-28)

`planner-references-v1` derives a primary model-facing projection from the
canonical planner response. Model bindings use resource_refs with a nonempty
array of request-local resource1/resource2 references. Device/source identity
properties are omitted from wire choices; the server retains their authorized
mapping. Normalization permits a scalar deviceId, an array of deviceIds or a
sourceId with matching resource kind. fact_ref and dependent step_output retain
their existing meanings and graph validation. Unknown/mismatched references and
literal UUID bindings are rejected, never heuristically resolved.

New goal proposals supply goalId/text; the server attaches the exact current
messageId. Retained goals and their historical sources remain untouched. This
is transport lowering, not server interpretation of requested metrics/widgets.
The canonical protocol, stored response shape and legacy readers are unchanged.
Wire validation and lowering run before canonical checks, inside the existing
A2 reservation/usage/error boundary. Canonical ledger replays bypass lowering.
Correction input raises a canonical rejected proposal back into the same wire
language; no extra retry or budget is introduced. The projection version and
planner-routing-v4 guidance are pinned in the primary policy fingerprint.

Planner ASK is confined to intent/target routing ambiguity. Domain fields such
as name or widget choice go to the selected skill using exact user text/history.
Dashboard-review choices include actual accepted name and resourceType=DASHBOARD,
status and requiredIntent, so selected telemetry devices do not obscure the
active dashboard subject. CONTINUE on a WAITING_CONFIRMATION dashboard is rejected
before another STEP; a new change needs AMEND, while execution still requires
the separate confirmation control. No natural-language keyword router is added.

## Delegated design and exact requests

The dashboard skill treats explicit user instructions as constraints and chooses only unspecified aspects. Delegated requests leave metric selection, compatible views, grouping, size and order to the model. There is no device-specific template, mandatory widget bundle, server semantic preprocessor or separate selection/layout model call. General number/string/boolean/format examples explain data decisions. Capability metadata is factual input. New follow-ups go through planner; only real pending-step-question answers directly continue a skill. Reviews and receipts are revalidated before model I/O.

FIRST_PASS_DIAGNOSTIC stays correction/judge-free. Explicit opt-in VERIFIED adds read-only compiled-result assessment before PlanStore.save through the existing A2 ledger, PINNED model, output policy and limits. STANDARD defaults stay unchanged. Assessment sees original dialogue, accepted definition/inventory, capabilities and actual candidate; writer goals/masks/prose are excluded from its wire input. Exact user quotes, candidate digest and widget evidence are checked. Rejection/uncertainty prevents READY; existing bounded domain repair may handle a concrete rejection, without verifier format retry or another budget. Same-model assessment is not a statistically independent oracle.

Additive assessmentResponse and VERIFY readers precede writers. A2 records OUTCOME_ASSESSMENT separately from recovery. Known zero-call pre-model failures have complete zero accounting but never count as generation successes. Missing traces/uncertain reservations still halt. Real-model calibration and browser aesthetics remain evidence gates.


### Planner capability and active subject follow-up

The catalogue derives dashboard operation names from the existing per-intent
command schema. inputKeys constrain resource binding serialization, not supported
user requests; descriptions participate in the registry policy digest. This
engineering correction does not semantically validate UNSUPPORTED or ASK decisions.
Campaign06 showed that the model can replace a forbidden domain question with an
unnecessary intent/target question. Merely expanding guidance is not accepted as
a demonstrated quality improvement.

The offline-only planner-references-v2 projection makes bindings field-specific
using declared catalogue fields and the authorized resource snapshot. Review
references remain readable context but are not eligible device/source bindings.
Choices expose bindingFields. activeSubject is emitted only for a dashboard-review
whose exact identity matches stored dialogue.focus; its selected devices receive
CONTEXT_RESOURCE roles. UI_CONTEXT metadata does not claim that an inherited UI
device is the user's new edit target. Initial creation or an unmatched focus does
not acquire a fabricated subject. Canonical request/history, raw user text and
intent selection remain unchanged; no keyword router or hidden model switch.
This v2 follow-up still needs actual model replay before claiming quality benefits.


### Context-first clarification

The common trust policy and planner-routing-v7 instruct the model to resolve a
short follow-up against exact text, mandatory history, stored focus/activeSubject
and confirmed facts before asking. Omission of an already-established subject is
not itself ambiguity. Hypothetical alternatives or a contextual device do not
replace the current work item. Explicit new tasks remain model-selectable; no
server natural-language classifier or forced dashboard routing is introduced.
Ask only when unresolved meaning changes the safe next action, and use loaders
for missing domain data. The common policy digest participates in runtime pinning.

Campaign07 tested this together with the previously offline-only v2 references.
It failed to eliminate unnecessary rename clarification (2/5 first-pass,3/5 final,
1/2 dialogues). This clarifies the intended contract without claiming the model
reliably follows it. Full acceptance remains open and previous failed scores stay.

## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.

## Requirements and retrieval amendment (2026-11-03)

The [AI collaboration/retrieval specification](../../../docs/target/ai-assistant-collaboration-and-retrieval.md) is normative for these pending extensions: typed requirements, bounded consultations, exact metric evidence, optional ACL-aware pgvector retrieval and all-call budgets. This extends existing G7/A5/A6/G8 ownership; runtime and live trials remain stopped. Existing frozen acceptance/checked-task evidence is retained.

## Second implementer audit

Specification revision2 in the [collaboration contract](../../../docs/target/ai-assistant-collaboration-and-retrieval.md#9-уточнения-второго-аудита-и-повышение-точности) clarifies dependency-specific rebase, dual question/requirements CAS, exact absolute deadlines, ID-only private retrieval, requirement coverage, evidence authority and controlled quality ablations. AI01-AI24 are runtime gates; passing documentation checks is not implementation evidence. Existing completed tasks/pinned policies remain unchanged.

## Spring implementation architecture (2026-11-03)

The [Spring implementation contract](../../../docs/target/ai-assistant-spring-implementation.md) and [proposed ADR-014](../../../docs/adr/ADR-014-ai-spring-extension-boundaries.md) define exact bean/port/advisor/AOP, transaction, worker, dependency and observation boundaries. S0-S6 extend existing A5/A6/G7/G8 ownership; SPR01-SPR15 supplement existing semantic/quality gates. No second ledger/memory, hidden SDK loops or automatic implementation/live authorization is introduced. The completed Spring AI migration is preserved.
