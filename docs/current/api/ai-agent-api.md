# AI Agent API

`ai-agent-service` owns the operator assistant planning surface and per-account
model provider configuration. The browser reaches it through the API Gateway
under `/ai-agent/**`; the service runs on port `8088`.

## Current Implementation

### LangGraph4j core integration (2026-11-11)

New assistant plans pin `langgraph4j-1.9.9` / `voltcanary-agent-graph-v1`.
The authenticated assistant profile adds `graphEngine` and `graphDefinition`
while preserving `engine=spring-ai-2` and existing generation gates. Public
turn, question, review and confirmation request shapes are unchanged.

Active pre-graph plan generation returns409 `GRAPH_ENGINE_MIGRATION_REQUIRED`;
an incompatible engine/definition returns409 `GRAPH_ENGINE_VERSION_CHANGED`.
No generator fallback or implicit in-place migration runs. Existing history,
cancellation and original saved-review execution remain available. A new
conversation can explicitly reference an authorized prior review. Questions,
confirmation pauses, request deduplication and uncertain execution retain their
existing user-facing semantics. Persisted schema2 is internal and does not
change the canonical `assistant-v2` wire version.

For a registered single-text name clarification, `GET /ai-agent/plans/{planId}/requirements`
returns the active question and requirements revision. Submit the name with
`POST /ai-agent/plans/{planId}/requirements/answers`, including the question ID,
both revisions, a request ID and a `TEXT` answer. The answer commits the name
and makes the step ready. A subsequent conversation message with `continueStepId`
prepares that step on the same plan. `GET /ai-agent/plans/{planId}/consultations`
returns the statuses shown in the assistant drawer.

[Runtime and recovery boundaries](../architecture/spring-ai-assistant.md#langgraph4j-core-integration--2026-11-11)
describe atomic checkpoint storage, version fencing and remaining acceptance.

### Spring AI 2 cutover (2026-10-29, offline validation)

All new plans, clarifications and conversation turns use the durable primary
runtime with Spring AI 2.0.2 on Spring Boot 4.0.9 / Spring Cloud 2025.1.4.
[Runtime architecture](../architecture/spring-ai-assistant.md) describes the model,
message, tool and execution boundaries. Account-scoped provider credentials and
PINNED model selection remain authoritative. OpenAI now uses Chat Completions;
Anthropic uses Messages and Gemini uses GenerateContent. Compatible providers
retain their registered Chat Completions endpoint. `/model-provider-capabilities`
reports `OPENAI_CHAT_COMPLETIONS` for OpenAI.

`AI_ASSISTANT_V2_ENABLED=false` disables generation with HTTP 503
`ASSISTANT_GENERATION_DISABLED`; it no longer selects the former generator.
The deployment default remains false pending quality qualification. Saved review
reads, cancellation and execution remain available without a model call. A legacy
review clarification imports its authorized base into a new durable plan.
`/orchestrations/runtime` keeps `generation=assistant-v2` while enabled and adds
`engine=spring-ai-2`; while disabled generation is `disabled`. No rules/model
fallback is reported by `/provider`. Existing conversation/review API shapes
remain compatible. Historical sections below describe their respective milestones;
this cutover supersedes their statements about an active legacy generation path.

SDK retries and the automatic Spring AI tool loop are disabled. Each primary
exchange, correction and context continuation enters the shared reservation ledger.
Native tool output is a reviewed proposal, never authorization for a mutation.
The bounded persisted dialogue becomes typed Spring AI messages while its exact
pending question and source identities remain available to the planner/skill.
Model quality and A5/A6 workflow acceptance remain unqualified; offline framework
and contract tests do not change those historical results.

### Local primary integration (2026-10-27, qualification pending)

Review-edit recovery now retains the accepted review and its output bindings
through PREPARING and failed preparation. These states cannot be confirmed.
A successful replacement atomically supersedes the previous READY review in
the same JDBC transaction that commits the new orchestration state. An explicit
new user turn may continue a failed preparation with a retained review, without
repeating execution or routing by message keywords. Failed execution receipts
remain subject to reconciliation rather than this preparation continuation.

`ConversationDetail.acceptedReview` is an optional ordinary PlanResponse for the
last accepted review when the current response is a question or failure. The
frontend restores it as the existing read-only retained preview, including on
reload. The current failure remains visible and cannot trigger automatic answers
or confirmation of the retained preview. A synthetic orchestration response ID
submitted as a continuation is resolved to the owned accepted review through
typed state identity; it is not treated as a new domain review.
During PENDING or interrupted UNKNOWN requests, `plan` is null and
`acceptedReview` remains available only for the retained preview. The existing
conversation request journal supplies that status; polling never invokes a model.
Independent postconditions reject collateral layout order changes at every
breakpoint and prohibit layout changes for edits without geometric targets.
The primary planner's proposed PLAN/AMEND also passes the existing pure
`IntentPlanGraph` checks inside the budgeted proposal boundary. Unknown facts,
goal references and dependency failures therefore receive the same single
diagnostic correction; another invalid graph stops before review preparation.
Technical correction cannot introduce a different nonempty goal-supersession set.
Questions and option bindings may name only declared skill parameters. Dashboard
selection questions use declared device/source/widget keys and period/name fields;
invented technical fields such as `topRowPosition` are rejected for correction.

On refinement, the accepted review supplies current values. Initial planner
literal parameters remain in durable history but are excluded from the current
resolved inputs and projected bindings; receipt-backed facts and step-output
dependencies remain mandatory. This prevents an old proposed name from
overriding the user's current exact rename.
Format correction includes schema-derived allowed/required fields for each
rejected operation discriminator. It does not rewrite operations, infer intent
from message text or bypass validation. Invalid JSON receives the same bounded
single correction and every attempt remains in the shared budget ledger.

Saved-review confirmation and typed controls use `IntentPlanActions` directly.
They perform no model resolution or provider call, so disabling a provider cannot
prevent cancellation or execution of an otherwise valid immutable review.

Under the user's explicit local cutover request, `AI_ASSISTANT_V2_ENABLED=true`
enables Spring AI generation for conversation submits, `/plans` and clarifications.
The repository/deployment default is `false`. Restart only `ai-agent-service`
after changing this environment value; do not recreate databases or volumes.
On Windows, missed frontend bind-mount notifications may leave Vite's served
modules stale. Restart only `frontend` before browser verification when the
container source and displayed UI differ; a host build alone does not prove the
running browser received the new code.
Compose mounts the canonical target contracts read-only for Maven packaging.
`GET /ai-agent/orchestrations/runtime` requires normal authentication and reports
the active generation, PINNED routing, `hiddenFallback=false`, `qualified=false`.
The ordinary `/ai-agent/provider` status also reports `fallback=none` in this mode.
The A1–A4 sections below retain their historical implementation milestones;
production wiring is now supplied by `PrimaryAssistantConfiguration`.

The first turn pins the configured model for the durable plan. The current local
transport uses Spring AI provider models; see the 2026-10-29 cutover above.
There is no automatic legacy or model fallback. The 13 existing tool names and
UPDATE_DASHBOARD_DRAFT have registered server packages. Separate HSL source and
versioned persisted-draft execution paths now exist, but complete workflow parity
and V/R acceptance remain **not qualified**; see the 2026-10-28 slice below.
Raw current messages and actual questions/history go through A3/A4; semantic
regex extraction is not used by the new generation path. Domain compilation,
ordinary immutable reviews and explicit confirmation remain required.

The integration reuses the existing domain implementation, rather than introducing
a second dashboard builder:

| Responsibility | Shared existing implementation used by v2 |
| --- | --- |
| Scoped device/source/widget capabilities | `AuthorizedPlanningContext`, `DashboardPlanningContext.enrich` and dashboard composition capability APIs |
| Typed widget operations and preservation masks | `DashboardOperationProtocol`, `DashboardOperationSchema`, `DashboardOperationCompiler` |
| Prepared definition, bindings, grid and digest | `DashboardCompositionCompiler` → `/dashboards/composition/prepare` |
| Exact keep/undo of saved definitions | `DashboardReviewHistory.restore`, with a scoped v2 parent-link adapter; no second ancestry/copy implementation |
| Immutable review persistence | `PlanStore.save` and existing review readers |
| Confirmed domain execution and receipts | `ExistingOrchestrationReceipts` → `AiAgentOrchestrator.execute` → existing `ToolExecutor`/dashboard creation executor |
| Preview | Existing shared `DashboardDefinitionPreview` and review/confirmation components |

`PrimaryDomainPreparation` adapts the registered command to these methods. It
bypasses the old natural-language guards in `DashboardCreationCompiler`; it does
not replace that compiler's underlying operation/composition implementations.
New structural verification checks proposals before saving; it is not another
definition builder. Full legacy workflow parity remains an unfinished A5 task.

Verification checkpoint (2026-10-28 local time): the focused backend suite passes
37 tests, frontend conversation hydration passes 9 tests, and frontend typecheck,
build and strict OpenSpec validation pass. Browser reload restores an accepted
review separately from the failed candidate, without an apply control. The final
two-family real-model replay (`live-12`, one trial each, original frozen corpus)
passes **1/5 turns and 0/2 complete dialogues**. No dashboard was executed.
The first creation failed domain preparation and its continuations failed to
recover. The second creation passed; its READY move failed requested top-placement
and untouched-order assertions. Structural verification currently compares the
compiled definition with model-selected operation targets/masks, so an incorrectly
selected target may still pass it. This is an open V/R semantic acceptance gap,
not a qualified release. All live reports remain separate ignored artifacts;
the local primary override remains explicitly enabled, with `qualified=false`.

Planner resourceChoices include explicit selected devices only after an owned
domain read. Selection survives short replies via the existing scoped request
journal; a selected saved review supplies the actual revised dashboard scope.
History beyond the bounded selection lookup fails explicitly rather than silently
forgetting a required selection. Dashboard command schemas contain the operations
envelope alone; provider projections bind the exact registered command schema ID,
version and payload. Legacy plan wrappers are rejected as command payloads.

The operational policy reserves at most six calls per turn and 60 per parent
plan (including repair/context continuations), 240 seconds per turn and one hour
per plan. The configured provider timeout further bounds each call. Output is
reserved at up to 12,000 tokens including reasoning, bounded further by the
configured model's output limit. With no verified price snapshot,
each call reserves USD 0.10 conservatively; USD 0.60/6.00 are reservation ceilings,
not measured invoice costs. The local model is unqualified for AUTO selection.
During the local `gpt-5-nano` check the account model timeout was raised from
60,000 to 120,000 ms after an observed 60,052 ms transport failure. Provider/model
identity, credentials and other sampling/output settings were retained. The
previous timeout is recorded in the ignored cutover evidence. Changing these
operational model settings changes the plan policy fingerprint; start a new plan
rather than silently changing an existing plan's pinned policy.

V010 adds orchestration-to-review bindings, stable workflow context snapshots
and optional conversation revision checks. Existing transcripts, traces,
reviews and receipts are reused. `ConversationDetail.orchestration` adds plan
revision/status, ordered steps, exact pending question and focus. The shared
preview consumes the ordinary PlanResponse. Typed `POST
/ai-agent/orchestrations/{id}/control` accepts the canonical versioned
control request with expected revision and request ID. Failures retain state;
the server never retries a domain mutation because diagnostics failed.
Widget descriptor references renew through the dashboard composition catalogue,
including checking unchanged descriptor settings.
One invalid JSON or structured proposal may receive a format correction with schema-owned
validation locations and the untrusted rejected public proposal. It reserves a
new call ID in the same turn/plan; a second invalid response stops. No transport
timeout or uncertain exchange is automatically repeated. Incomplete responses
retain their reported usage and fail explicitly. RETRY controls make a failed
step eligible again; a subsequent explicit message starts preparation.
Registered skill violations include their own schema locations rather than an
empty canonical-schema diagnostic. Malformed JSON is returned to the same model
as untrusted public text for that one correction; it is never extracted from
prose, repaired with keyword rules, or treated as executable commands locally.

Rollback sets the flag false and restarts the AI service; v2 history/readers and
saved-review confirmation remain available. This local override is not a global
default enable or release qualification. Live frozen-corpus reports are kept
separately under ignored `artifacts/ai-v2-cutover-20261027`; failed runs retain
their original scores. Planning tests do not execute dashboard mutations.

### Assistant-v2 contract boundary (A1, 2026-10-26)

`orchestration.contract.AssistantProtocol` supplies typed Java records for all
canonical envelopes. `ProtocolValidator` loads the authoritative target JSON
Schema as a Maven classpath resource, validates draft 2020-12 including formats,
rejects extra authority/execution fields, duplicate JSON keys, trailing JSON,
unknown definitions and incompatible protocol versions. Provider projections
contain only the selected definition and its transitive dependencies. A provider
projection never replaces canonical local validation.

`SkillRegistry` accepts deployment-reviewed packages only: versioned manifests,
SHA-256 instruction digests, input/output/command schemas, parameter requirements
and proposal policies. Catalogue summaries are filtered by server-provided
authorized intent IDs and capabilities; resource refs do not grant access.
Unknown intents/skill versions/schema IDs/loaders fail before execution.
PREPARED and NO_CHANGE remain proposals: ordinary domain checks, immutable
reviews, explicit confirmation and receipts establish actual outcomes.

At the A1 milestone this was an internal contract boundary without HTTP writers.
The local primary integration above now supplies them. A3 owns storage of raw messages, exact pending questions and resource
continuity; A4 owns bounded dialogue assembly. The canonical contracts carry these
fields now. A5 supplies production skill packages and migrates NL interpretation.
Existing `/plans`, conversation/history/trace readers and exact saved-review
execution remain in place. Future writers must explicitly negotiate
`assistant-v2`; reject unknown versions instead of silently selecting legacy NL
generation. Rollback disables v2 generation while retaining readers and receipts.

Legacy parity inventory (replacement owner A5, with A3 durable state and A4
dialogue/capability context):

| Supported scenario | Existing boundary / parity fixtures to retain |
| --- | --- |
| CREATE_DEVICE, ordinary registered types and optional location | DeviceCreationCompiler; DeviceCreationCompilerTest, DeviceIntentFixtureTest |
| HSL device and source handoff | DeviceSetupExecutor, DeviceConnectionIntent; DeviceExecutorBoundaryTest |
| CREATE_DASHBOARD and EDIT_DASHBOARD, movement/resize/settings/data/undo | DashboardCreationCompiler, DashboardOperationCompiler; DashboardIntentFixtureTest, DashboardExecutorBoundaryTest, operation/compiler fixtures |
| CREATE_DATA_SOURCE | ActionPlanner/ToolExecutor ordinary source review and execution |
| CREATE_SIMULATOR_CONFIG, VALIDATE_SIMULATOR_YAML | ActionPlanner/ToolExecutor; existing simulator YAML validation and review |
| CREATE_ALERT_RULE, UPDATE_ALERT_RULE, TEST_ALERT_RULE, EXPLAIN_ALERT | ActionPlanner/ToolExecutor; existing rule access, testing and execution fixtures |
| OPEN_TELEMETRY_WINDOW | ActionPlanner/ToolExecutor; read-only scoped window |
| CREATE_DEMO_DEVICE, START_SIMULATION, STOP_SIMULATION | ActionPlanner/ToolExecutor; existing reviewed demo/simulation commands |

Semantic decisions requiring removal from v2, all owned by A5: ActionPlanner's
keyword/tool routing; DeviceTaskContext's create/name/type/referent extraction;
DeviceConnectionIntent's natural-language connection and transport-mode matching;
CreationPeriod's period phrases; DashboardTaskName and DashboardPlanningContext's
name/creation recognition; CreationTaskPlanning's question-text/name branching;
DeviceCreationCompiler and DashboardCreationCompiler's semantic name/template/
quantity/request guards; ConversationContinuation's pronoun/name matching.
A3/A4 replace DashboardTaskContext/DeviceTaskContext and
conversation referent selection with actual dialogue, explicit focus and
receipt-backed resource chronology; the model interprets that context.
Technical UUID/HSL-key/enum/grid validation, secret redaction and structured
operation checks remain server responsibilities. Retaining legacy generation
during migration is not parity completion or a fallback for a failed v2 proposal.

The repository-owned `AssistantProtocolScenarioTest` consumes the canonical
examples directly in Maven/CI and checks typed round trips plus incompatible
versions, missing current message/dialogue, unavailable skills and invented
execution claims. Schema validity does not establish semantic model quality.

### Assistant-v2 routing and budget boundary (A2, 2026-10-26)

`RoutedModelCalls` is the internal entry point for canonical planner/step calls.
It rejects unknown skills, versions and schema IDs before provider I/O, serializes
the complete provider body through a one-exchange adapter, then evaluates each
candidate's actual body size. No mandatory message, question, goal, choice or
resource event is removed to force a cheaper route. UTF-8 byte count plus an
explicit provider framing allowance is stored as ESTIMATED input tokens;
output reservation includes reasoning. Adapters receive the remaining turn/plan
timeout and must perform exactly one exchange; every continuation, repair or
format correction must re-enter the same boundary with a persisted call identity.

PINNED keeps the explicit model even during repair. AUTO requires the matching
profile/phase/intent qualification, current account access, allowed output mode,
full input/output capacity and an affordable price reservation. It chooses the
lowest reserved cost among eligible profiles; model changes require the profile's
allowEscalation permission and record a reason. `ExistingModelAvailability` uses
the existing scoped `AiModelResolver` for enabled providers/models/credentials;
qualification, capability and price snapshots are reviewed server inputs, with no
implicit brand-based qualification or credential/settings changes.

`ProviderProposalNormalizer` handles OpenAI-compatible, Anthropic and Gemini
proposal envelopes in allowed NATIVE_TOOLS / STRICT_JSON / JSON_TEXT modes. It
rejects incomplete/refused/multiple outputs and unexpected tool names; it never
repairs prose or calls a provider. All modes feed the same canonical and registered
skill/command validation. Provider output remains a proposal, with no mutation.

Migration V007 adds plan/turn budget rows and individual attempt records, linked
to existing conversations with cascading deletion. Each operation rechecks the
owner and conversation expiry. Transactions lock conversation, parent budget,
then turn in that order; reservations commit before network I/O. Policy/version
and ceilings are frozen for the plan/turn: opening again cannot reset counters,
extend deadlines or silently increase limits. Duplicate call identity with the
same request/route replays the validated outcome, or returns an already-started/
uncertain outcome. Changed content under that identity is a conflict. A3 must
persist call IDs before dispatch and retain them across request delivery/restart.

The ledger records all calls, estimated input, output reserve, cost, price and
context/skill/schema versions. Known usage reconciles reservations; unknown
usage/network completion retains the conservative charge. Actual overruns prevent
use of the proposal and consume the actual charge. Cached input is a subset of
input; reasoning is included in output or added exactly once according to the
versioned price snapshot. Rates carry source, currency and validity dates. Missing
or expired prices stop with PRICE_UNAVAILABLE unless the policy explicitly reserves
a conservative upper bound; unknown actual cost never proves savings.

`BudgetedModelCalls` emits BUDGET and MODEL_CALL events through the existing
`AssistantTraceRecorder`. Failure to reserve or persist a required trace start
prevents provider I/O. An outcome trace failure never replays the call: the ledger
retains its result. Cancellation blocks new reservations and late results;
unknown domain mutations remain the responsibility of existing receipt recovery.
History/reload/diagnostic reads perform no provider calls.

These are opt-in internal boundaries; existing provider/settings HTTP routes and
legacy generation remain unchanged. Proposed `/routing-policies` and v2 turn routes
are not exposed in this pass. A3/A4/A5 wire durable orchestration, context and domain
skills to this entry point before rollout. No production AUTO qualification,
provider portability/quality or cost-saving claim follows from offline fixtures.

Verification (two offline scenarios, no Docker or paid calls):
`mvn -pl services/ai-agent-service -am -Dtest=AssistantProtocolScenarioTest,AssistantRoutingBudgetScenarioTest -Dsurefire.failIfNoSpecifiedTests=false test`.
The budget scenario executes V007 against H2 (the existing V004 fixture uses a
TIMESTAMPTZ compatibility domain), tests competing reservations, restart/delivery,
parent ceilings, scoped denial, missing usage/prices, cancellation/late results,
deadline exhaustion and trace-start failure. Live PostgreSQL/provider and full
regression qualification remain for the later integrated acceptance.

### Assistant-v2 durable intent plans (A3, 2026-10-26)

`IntentOrchestration` now implements the server workflow behind the opt-in v2
writer boundary. Generation writers are still not registered: A4 supplies bounded
context assembly and A5 supplies reviewed domain skill packages/command compilers.
This is not a switch of the existing `/plans` or conversation UI to v2.

The durable state uses additive V008 with three tables: `ai_orchestrations`
(schema version 1 and revisioned typed state), `ai_orchestration_requests`
(request digest, lease, source-message link and stable call identities), and
`ai_orchestration_resource_events` (ordered references to existing action receipts).
All records are scoped through the existing conversation, user and account;
conversation deletion cascades. Plan expiry prevents new work without deleting
the conversation's confirmed resource chronology. Reading expired state projects
EXPIRED without writing or invoking a model. Unknown persisted schema versions
fail explicitly instead of being silently interpreted as the current version.
Referenced ordinary review/action receipts have their retention extended through
conversation expiry, including conversations configured longer than 30 days;
their execution expiry, version, digest and immutable payload do not change.

Input is committed to the existing conversation journal before remote work.
The v2 request journal keeps request metadata and a message ID; it reconstructs
current raw text from that same transcript record instead of copying history.
Each active request owns a revision/lease. Same-ID/same-payload delivery reads
state, while changed content conflicts. Checkpoints commit accepted plans and
PREPARING/EXECUTING state before the next external call. Cancel/new revision makes
late results stale; late model output cannot publish a new review or overwrite
the current plan. Reads and duplicate delivery do not restart interrupted work.

Planner PLAN/CONTINUE/AMEND/ASK/UNSUPPORTED decisions are canonical proposals.
`IntentPlanGraph` validates bounded graph size, unique IDs/inputs/dependencies,
registered version/digest, dependency cycles, forward output references and matching
input/output types. Retained pending goals require coverage or explicit supersession
from a new user turn. Completed, executing and UNKNOWN steps cannot be superseded.
Affected downstream pending reviews lose execution eligibility via
`PlanStore.invalidate`; their saved payloads and actual receipts are unchanged.

A single waiting step receives a reply/refinement directly. Its skill may return
REPLAN_REQUIRED, which invokes the planner with the same raw message and original
goals under the same A2 turn/parent budget. The coordinator does not route speech
using regex, keywords or the wording of a question. `RoutedIntentModels` checks the
actual message ID/text, exact answered-question payload and explicit routing/pinned
preference before entering A2. A4 remains the owner of full bounded history,
catalogue/context paging and mandatory-context selection.

Questions persist their server ID, exact text/choices, assistant-message reference,
step and revision. Receipt-backed facts retain their original input-message links.
Completed plans retain resource events and recent focus for new plans. A resource
ref never grants access: `ConfirmedInputs` reads successful action receipts and
revalidates current access through `ExistingOrchestrationReceipts`. Proposed,
failed and UNKNOWN results cannot satisfy a step dependency. Missing/deleted
selected resources fail without choosing another resource or calling the model.
The current A3 import is capped at 100 retained receipt events; more requires
explicit narrowing/context paging rather than silent omission (A4).

`IntentPlanActions.confirm` requires the exact step/review/version/digest at the
expected plan revision; the server also compares the immutable action list and
current access. It checkpoints EXECUTING, then delegates to existing reviewed
execution. The stored action receipts establish actual outputs. A failed later
step preserves earlier successful steps/resources. RETRY on UNKNOWN/executing work
uses ordinary receipt reconciliation; it cannot re-create a completed dependency.
An interrupted dispatch with no conclusive receipt stays UNKNOWN. A partially
successful multi-action review requires explicit amendment instead of replaying
its successful actions. NO_CHANGE remains WAITING_DATA until the V owner supplies
server postcondition verification; model assertions do not establish success.

CANCEL invalidates pending reviews, retains performed results and blocks the A2
parent budget. In-flight execution remains available for explicit receipt
reconciliation. RESUME/RETRY are revisioned typed controls, with no semantic parser,
model call or independent budget reset. Retrying failed preparation makes the step
eligible for a new explicitly initiated preparation/review; it does not execute a
mutation. The model does not authorize future unknown review payloads.

New authenticated read-only routes:

| Method and route | Response |
| --- | --- |
| `GET /ai-agent/orchestrations/{id}` | Canonical `plan.state`; 409 while an initial ASK has no accepted steps |
| `GET /ai-agent/orchestrations/{id}/recovery` | Head recovery `{planId, conversationId, revision, status, plan, pendingQuestion, focus}`; `plan` may be null before initial planning |
| `GET /ai-agent/conversations/{id}/orchestration-resources?after=0&limit=50` | Ordered receipt-reference events; `after` is the last sequence, `limit` is 1..100 |

These readers do not reconcile execution, call AI or mutate state. Existing
conversation history/review readers remain usable. New generation, confirmation
and control HTTP writers plus frontend recovery activation remain opt-in integration
work; no v2 generation flag is enabled here. Errors use existing authentication,
404 scope, 409 revision/confirmation and 422 typed-contract boundaries.

Narrow verification:
`mvn -pl services/ai-agent-service -am -Dtest=IntentWorkflowScenarioTest,IntentPlanningSafetyScenarioTest -Dsurefire.failIfNoSpecifiedTests=false test`.
Two scenarios exercise actual V008 SQL/JDBC, existing immutable review storage
and the existing execution/receipt adapter, with simulated provider/domain
transport. They cover multilingual fixture messages, two-device graphs, optional
fields, short replies/reload, partial workflow success, UNKNOWN reconciliation,
cross-plan identity, deleted resources, account denial, review supersession,
same-budget replanning, deduplication and late cancellation. This is offline
boundary evidence, not live language-quality acceptance. PostgreSQL, frontend/UI,
full regression and live-provider qualification remain unrun per the user's
limited-check request; A3 task 1.9 remains unchecked and the change is not archived.

### Assistant-v2 bounded skill context (A4, 2026-10-26)

`BoundedSkillRequests` implements the A3 `RoutedIntentModels.Requests` port.
The planner receives only the authorized intent catalogue, retained goals, confirmed
receipt facts, dialogue and resource chronology. The step receives its exact pinned
skill/version/instruction digest, parameter proposal rules, input/command schemas,
typed bindings/resolved inputs and registered loader contracts. Proposed inputs
remain data; they do not become confirmed facts. Interpretation of names, periods,
types and short replies belongs to the model. This path has no semantic regex or
question-wording classifier; existing legacy generation is retained until A5 parity.

Canonical `step.request` adds optional `contract` and `loadResults` fields so saved
A1 examples remain readable. The A4 builder always supplies them. `contract`
contains server-owned parameter/schema/trust policy metadata; `loadResults[].data`
and user/resource labels are untrusted data. Provider role separation remains
mandatory when A5 wires production transports. `SkillRegistry` checks projected
rules, schemas and loader arguments against the registered package. Exact immutable
base review/version/digest/actions are read through `ScopedReviewContext`, including
a review superseded for refinement; ordinary permissions and resource access are
rechecked and the existing credential/depth guard remains in force.

The mandatory dialogue kernel retains source messages for every retained goal,
fact and resource event plus the exact answered question and all choices. The raw
current message is at the request root, without duplication in `dialogue.messages`.
Up to six recent neighbours fill the remaining window of at most 12 messages.
Only optional neighbours can be removed for size; counts/cursors/completeness
disclose every omission. User confirmation/control events project to wire `INPUT`;
ordinary execution entries project to `EXECUTION_RESULT`. This reads the existing
transcript without reconciliation or summarization.

All retained resource events (up to 24) and confirmed facts (up to 100) survive in
chronological order, including across plans. Excess candidates or a mandatory
kernel that cannot fit return a narrowing/context error, rather than manufacturing
a unique candidate. Full request bytes, conservative UTF-8 byte/token estimates,
the selected skill limit and protocol depth/size limits apply; A2 subsequently
measures the complete provider body including response schemas/framing.

Registered read-only domain adapters:

| Loader | Ordinary scoped API |
| --- | --- |
| `device.catalogue`, `device.types`, `device.read` | `GET /devices?limit=101`, `/device-types`, `/devices/{id}` |
| `location.catalogue`, `source.list` | `GET /locations?limit=101`, `/devices/{id}/data-sources` |
| `dashboard.read` | `GET /dashboards/{id}`; actual definition and revision |
| `dashboard.widgetTypes` | `GET /dashboards/composition/widget-types` |
| `dashboard.capabilities` | `POST /dashboards/composition/capabilities`; declared devices/period/purpose, no duplicated capability matrix |
| `conversation.history` | Bounded authorized reads of the existing transcript |

Domain lists exceeding 100 choices fail and require narrowing; no competing
candidate is silently removed. Source projections exclude adapter configuration
and credential references. Actual empty data, freshness/no-data diagnostics and
capability availability are preserved; a deleted/forbidden resource fails, and a
dependency failure returns `CONTEXT_DEPENDENCY_UNAVAILABLE`/503 rather than an
invented field or a request to clarify user meaning.

`NEED_CONTEXT` validates the selected skill's loader allowlist and argument schema,
then validates returned data against the server loader result schema. Opaque UUID
cursors persist in V009 `ai_context_cursors`, bound by a digest of user/account,
conversation, plan, step, pinned skill and exact arguments. They expire after at
most 15 minutes/plan expiry, survive restart and undergo current access checks.
History pages freeze an upper sequence so later messages do not change a page.
Expired cursors are purged with the ordinary conversation cleanup interval.

At most three context continuations and 16 loaded result envelopes are permitted;
all retained data remains in the request, with partial snapshots explicit. Every
model continuation and A3 `REPLAN_REQUIRED` uses A2's original turn/parent budget;
there is no additional retry counter or model switch. Ownership/cancellation is
checked before more loading and provider I/O. Context load traces retain only
loader, snapshot and completeness metadata, never authorization or raw upstream
responses. No new HTTP writers, production skill packages, generation flag or
frontend behavior are enabled by these factories/read adapters.

Narrow verification:
`mvn -pl services/ai-agent-service -am -Dtest=IntentPlanningSafetyScenarioTest,SkillContextScenarioTest -Dsurefire.failIfNoSpecifiedTests=false test`.
Two offline scenarios use actual SQL/JDBC history/cursors/budgets/reviews, mocked
domain HTTP and provider responses. They cover canonical typed round trips,
question/reload/short replies, optional rules, registered paging, cursor misuse,
credential exclusion, injection/data separation, empty/deleted/unavailable sources,
mandatory overflow, two receipt-backed candidates across plans, same-budget
context exhaustion, REPLAN_REQUIRED, supersession and cancellation. These checks
establish context/accounting boundaries, not live model interpretation quality.
PostgreSQL, browser/UI, full regression and paid provider trials remain deferred.

### Created-device continuations (legacy generation)

For a new dashboard turn in an existing conversation, the server resolves
references such as "for this bus" before loading capabilities or calling a model.
Explicit `context.deviceId`/nonempty `deviceIds` overrides history. Otherwise an
explicit recorded device name wins, followed by the most recent unambiguous
successful CREATE_DEVICE receipt in this conversation. Proposed, failed, running
and unknown results establish no created-device fact. A successful create action
inside PARTIAL_SUCCESS remains usable. All reads use current user/account scope;
resource ownership is revalidated on every use. Deleted/inaccessible selections
fail without falling back to another device. Missing/ambiguous references ask for
an actual device selection before model work.

The new dashboard task stores selected device IDs, per-device source selections
and typed provenance (`conversationId`, `reviewId`, `actionId`) independently of
the device task. Receipt persistence remains authoritative across reload; no
resource identity is inferred from transcript text or a proposed model action.
`REFERENT` trace events identify explicit selection, receipt resolution or a
selection question. Actual device/source capability snapshots precede composition.
Missing/stale telemetry is preserved as availability evidence, never substituted
with invented measurements.

Device output receives one deterministic normalization pass for uniquely
equivalent `deviceName`/`deviceType` aliases and an exact redundant fixed HSL
intent. `typeRef` is accepted as an alias only when it uniquely matches a current
device-type catalogue ref or code; it is converted to `deviceTypeRef` or `type`.
Conflicting/expired/ambiguous aliases, network destinations, credentials and unknown fields
remain rejected. Final device type/name and connector identity still pass ordinary
server compilation and safety checks. A rejected device contract receives at most
one same-model repair with the original request and known facts; the second output
passes the same normalization and sanitizer gates. Every call and repair is traced.
Repeated incompatible output remains a technical failure without tool execution.
Device creation uses a dedicated prompt; strict-output OpenAI models receive
current catalogue type/location enums and nullable optional manufacturer/model/
location fields. This covers every current device type, including custom types
and ordinary non-HSL creation. Other tool workflows retain their generic planner.
Technical contract/preparation failures
return `planningStatus: FAILED`, no clarification suggestion, and a failed
conversation turn. An earlier accepted preview is mentioned only when present.

### Observable conversation traces

`GET /ai-agent/conversations/{id}/turns/{turnId}/trace?after=0&limit=100`
returns `{items,nextCursor}` in immutable sequence order. The cursor is the last
returned sequence, page size 1-100. `GET` on the same route with `/export` returns
an authenticated JSON attachment. Access uses signed identity and actual current
account scope; another owner receives404. History message `turnId` identifies the
submitted turn; request/message IDs stay stable on duplicate delivery.

Version1 events carry `id`, `schemaVersion`, `sequence`, `conversationId`,
`messageId`, `turnId`, `requestId`, `attemptId`, optional `reviewId`, `actionId`,
`modelRunId`, `stage`, `status`, `code`, `durationMs`, `details`, `truncated` and
`createdAt`. Nested spans include `parentAttemptId` in safe details. Each actual
provider adapter invocation receives a separate attempt; candidates and checks
correlate with that attempt. Existing model-run/audit/execution storage remains
authoritative; trace capture does not introduce a parallel execution receipt.

Stages include context/resource reads, capability snapshots, model calls and
responses, JSON parsing, candidates, sanitization, compilation, validation,
review persistence, execution and reconciliation. Statuses are `STARTED`,
`SUCCEEDED`, `FAILED`, `UNKNOWN`, `OBSERVED`, `RECEIVED`. A model response or
successful provider call does not imply a READY review or successful execution.
The UI renders observed stages and shows elapsed waiting without fabricated
stage progress; details and export are available from each submitted message.
Old conversations correctly have no historical trace.

Defaults:256 events/turn,8192 UTF-8 payload bytes/event,262144 payload bytes/turn.
Configure `app.ai.traces.max-events` (16-1024), `max-payload-bytes` (512-65536)
and `max-turn-bytes` (4096-1048576). Collection/depth/string limits also apply.
Oversized payloads retain a truncation flag, safe field paths and digest rather
than partial invalid JSON. Turn limits reserve a readable CAPTURE_EVENT_LIMIT or
CAPTURE_BYTE_LIMIT marker and block further capture-dependent side effects.
Secret values are redacted before writes; headers,
raw upstream bodies, system prompts and private reasoning fields are excluded.
Exact safe rejected field names remain available. Events expire/cascade with
their conversation; separate reviewed-task audit retention is preserved.

OpenAI `PROVIDER_USAGE` events record only allowlisted response status/incomplete
reason, configured output limit, available numeric input/output/cache/internal
token counters and final-text character count. `internalTokenCount` is a reported
usage number; hidden reasoning text and raw provider output are never stored.
Unavailable counters are absent. A completed provider response still does not
prove a complete or correct requested dashboard outcome.

Strict semantic responses enumerate only current capability dataRefs. Initial
composition omits keep/restore/remove/set-data operations requiring a base review.
Renewed refs include a short ASCII field/family hint plus a random namespace;
they remain opaque scoped references. Never construct refs from a field name or
reuse old refs instead of the current capability snapshot.

A failed preparation retains its dashboard task for subsequent short replies.
Execution terminal states still start separate work. Legacy provider failures
may retain `planningStatus: NEEDS_INPUT` while task/pending status is `FAILED`;
the transcript failure must not trigger a clarification-default timer. Unified
failure mapping remains owned by the later recovery stage.

Required turn/provider/action starts commit before calls or mutations. Required
outcomes are flushed before a later side effect; unavailable capture blocks the
next call rather than repeating prior work. Optional detail loss is explicit.
Outcome capture after a domain mutation never causes that mutation to be retried.
After ten minutes, unfinished starts reconcile from existing scoped execution
receipts where available, otherwise append UNKNOWN/PROCESS_INTERRUPTED. No
provider call or domain command runs during trace reads/export/reconciliation.

### Complete assistant conversations

The shared assistant uses these additive owner/account-scoped routes. Existing
plan and execution routes remain compatible.

| Method | Route | Contract |
| --- | --- | --- |
| POST | `/ai-agent/conversations` | `{conversationId: UUID}`; idempotently creates and selects a chat; owner is resolved from JWT and authorized current account scope. |
| GET | `/ai-agent/conversations?before={id}&limit=30` | `{conversations:{items,nextCursor},activeConversationId}`; newest creation first, stable `(createdAt,id)` cursor; page size 1–100. |
| PATCH | `/ai-agent/conversations/{id}` | Selects an owned conversation; no model or domain execution. |
| GET | `/ai-agent/conversations/{id}` | `{conversation,plan,resourceStatus,pendingStatus}`; authorized current review/task/receipt projection. |
| GET | `/ai-agent/conversations/{id}/messages?after=0&limit=100` | `{items,nextCursor}`; increasing immutable sequence; cursor is the last returned sequence. |
| POST | `/ai-agent/conversations/{id}/messages` | Submits text and returns current detail after preparation; no domain execution. |

Submission fields: `requestId` (UUID), `expectedRevision` (nonnegative integer),
`text` (1–2000 characters), optional `context`, `parentPlanId`, `providerId`,
`modelConfigId`, existing dashboard turn `metadata`, `automatic`, `displayText`.
Input is committed before provider invocation. Matching requestId/payload returns
the recorded status, including failure, without preparing again. Changed payload
under the same ID, stale revision, or a second pending submission returns 409:
GET current state before explicitly submitting a new request. Validation returns
400, missing authentication 401, other owners/expired conversations 404.

Metadata: `id`, `title`, `revision`, `lastPlanId`, `createdAt`, `updatedAt`,
`expiresAt`. Messages: stable `id`, `sequence`, `requestId`, `role`, `kind`
(`INPUT`, `REPLY`, `EXECUTION`), display `text`, `redacted`, `automatic`, optional
`planId`/`taskId`/`turnId`, `outcome`, `createdAt`. Execution references point to
the reviewed plan's existing durable receipt/action IDs. Each append advances
the conversation revision; previous replies are never overwritten.

Pending status: `PENDING`, `COMPLETED`, `FAILED`, `UNKNOWN`. Preparation failures
are retained as replies without raw provider errors. A ten-minute interrupted
lease becomes UNKNOWN on recovery; reads never replay providers/tools. Receipts
are appended idempotently on reads, including after a lost execute response or
restart. Resource status (`NONE`, `AVAILABLE`, `EXPIRED`, `UNAVAILABLE`) separates
retained text from executable review availability. Restoring history never
confirms execution or auto-answers a clarification.

Default expiry is 30 days after chat creation. Configure
`app.ai.conversations.retention` with a duration between 1 and 365 days
(`APP_AI_CONVERSATIONS_RETENTION` through Spring environment binding). Expiry is
exposed and enforced on reads/writes; hourly cleanup cascades conversation
requests/messages and clears selection. Reviewed plan/action audit retention is
independent and is not deleted by conversation cleanup.

Credential patterns and secret fields are redacted before transcript persistence
and before submission reaches existing plan/task storage. Redacted messages are
labelled in the UI. Unsent text uses a separate owner-scoped local draft cache,
credential redaction and 30-day expiry; it is not submitted history. Draft storage
errors remain visible. Identity changes clear visible state and invalidate late
responses. Legacy last-plan pointers offer an explicitly labelled recovered task;
transcripts lost before this feature cannot be reconstructed.

Planning and execution:

```http
POST /ai-agent/plans
POST /ai-agent/plans/{planId}/clarifications
GET /ai-agent/plans/{planId}
POST /ai-agent/plans/{planId}/execute
GET /ai-agent/tools
GET /ai-agent/provider
GET /ai-agent/device-setups/{deviceId}
```

### Reviewed real-device setup

For assistant-v2 device creation, the proposal uses an installed device type
code and may reference a location from the authorized catalogue. Preparation
stores the actual location ID in the immutable review and enforces the
device owner's name and optional manufacturer/model length limits. Location,
manufacturer and model may be omitted.
An explicit location selection stays bound to that location during preparation.
For a dependent HSL connection, the review target must match the device ID
resolved from the plan, including a prior device creation receipt.
The planner offers a new HSL connection only when device integration setup
is enabled and the HSL adapter is registered. Preparation checks availability
again before saving the review.
The review retains the owner device reference for fresh access checks on
confirmation, including when the skill chose the device from its context.

Device tasks preserve the original device request and explicit name/type choices
through the existing durable clarification context. Plain name answers and
`Название: ...` do not require quotation marks. Device task routing and memory
are separate from dashboard task composition. Existing resource authorization,
review digests, confirmation and keyed device creation remain in force.

With `AI_DEVICE_SETUP_ENABLED=true`, an explicit supported HSL connection request
is compiled into one reviewed CREATE_DEVICE action including the bounded bus/tram
identity. The dedicated DeviceSetupExecutor uses the DeviceIntegration port and
HslDeviceIntegration adapter; model output cannot choose connector URLs or
credentials. Execution reserves the identity, uses the ordinary keyed device
API, provisions an API/REST source, assigns HSL fields and saves a private binding.
Unknown outcomes use the existing explicit creation reconciliation and the same
device idempotency key. Reconciliation does not create a replacement device.

Results distinguish SOURCE_NOT_CONNECTED (ordinary manual source setup),
WAITING_FOR_DATA (configured HSL source without fresh accepted GPS) and
TELEMETRY_READY (fresh accepted GPS evidence). A successful resource mutation
does not by itself establish live telemetry. The owner-authorized setup status
read renews device/source access; it returns no source tokens. Dashboard handoff
continues to select the created device ID and resolve current source capabilities
through the existing dashboard flow. Other integrations remain explicit manual
source setup until an adapter is provided.

Model provider configuration:

```http
GET /ai-agent/model-provider-capabilities
GET /ai-agent/model-providers
POST /ai-agent/model-providers
GET /ai-agent/model-providers/{providerId}
PUT /ai-agent/model-providers/{providerId}
DELETE /ai-agent/model-providers/{providerId}
POST /ai-agent/model-providers/{providerId}/set-default
POST /ai-agent/model-providers/{providerId}/test
POST /ai-agent/model-providers/{providerId}/rotate-secret
GET /ai-agent/model-providers/{providerId}/models
POST /ai-agent/model-providers/{providerId}/models
PUT /ai-agent/model-providers/{providerId}/models/{modelConfigId}
DELETE /ai-agent/model-providers/{providerId}/models/{modelConfigId}
POST /ai-agent/model-providers/{providerId}/models/{modelConfigId}/set-default
```

Requests use the normal gateway-authenticated bearer token. The service parses
the bearer token to scope provider and model configuration to the caller's
account. If no configured account provider is available, `GET /ai-agent/provider`
falls back to environment-level OpenAI settings when present and otherwise to
the rule-based planner.

AI planning has no application response deadline. All model adapters keep a bounded
connection timeout, but wait for the provider response without a read timeout.
The existing model `timeoutMs` field now controls connection establishment only;
its stored values remain compatible. The AI gateway route disables its response
timeout by default (`GATEWAY_AI_RESPONSE_TIMEOUT_MS=-1`); deployments can explicitly
override that policy. Other routes keep the existing five-second default.

The overlay shows “Модель думает…” and elapsed time while planning is pending,
including after 60/90 seconds. It remains visible above the composer and survives
minimize/reopen without resending. This is a pending-request indicator, not a
stream of private reasoning or a claim that a particular server validation stage
has started. Read execution and task recovery have their own actual client phases.
Provider/network failures still surface errors; the previous review and draft survive.
OpenAI incomplete responses caused by max_output_tokens are diagnosed as
MODEL_OUTPUT_LIMIT instead of trying to parse partial output. Clarification failures
return502 with safe code/message fields, retaining the parent review.

Provider secrets are write-only after submission or rotation. Responses must
not expose API keys or provider credentials.

## Reviewed tasks and recovery

The creation-v15 planner receives a separate `taskMemory` map of current task values.
Reviewed compiled intent is persisted immediately in the existing context_json
continuation state, including name, period, selected equipment and widget layout.
Question-only turns retain that state. Each clarification renews and authorizes
resource refs; memory is a preference snapshot, never an access grant.

NEEDS_INPUT responses may include `clarification`:
`{"missingFields":["name"],"suggestedReply":"model-proposed ordinary answer","suggestionLabel":"visible proposed value","suggestedValues":{"name":"model-proposed title"}}`.
The model declares unknown field keys and proposes the default; the UI does not
infer defaults by matching words in a question. A question about a known field is
sent back to the model once with the stored working values before any question is
returned. Every missing field needs a concrete scalar suggestedValues entry.
The ordinary reply serializes those values; arbitrary prose is never sent as a
default answer. Legacy responses without concrete structured defaults require
manual replies. Invalid proposals receive one internal model repair.

The overlay displays the supplied suggestion for60 seconds. Starting input or
IME composition cancels auto-answer for the entire question, even if the text is
then erased. Opt-out is explicit. The submitted automatic choice is labelled and
goes through the ordinary clarification endpoint. It cannot confirm mutations,
repeat a default for the same field, or retry itself after provider failure.
A genuinely different missing field may receive a new deadline and proposal.
Identity changes and task switches cancel old timers. No schema migration is needed.

`POST /ai-agent/plans/{planId}/clarifications` accepts `{ "message": "Мой обзор" }`
as a reply or refinement. The authenticated current owner must have a nonexpired
NEEDS_INPUT or READY task. It prepares a new immutable plan with a new ID/digest;
it never executes an action or changes the parent task. The resolved provider and
model remain pinned. Current catalogues/resource ownership are fetched again.

Replies retain original request text and explicit equipment selection. Server-owned
continuation metadata lives in existing context_json (no migration). Older tasks
recover explicit selections from saved references without promoting unselected
catalogue candidates. Previous model questions are untrusted intent hints; obsolete
refs are replaced by display labels and are not accepted as current resource IDs.
Plain names can answer a name question; a later explicit period overrides an older
period, while multiple conflicting periods within one answer require clarification.

Limits:1–2000 characters per reply, thirty replies,8000 characters for original request plus latest answer,
and the existing credential/depth/size guards. Invalid answers return400, foreign
tasks404, expired tasks410, executed/running/unknown tasks409 and exhausted rounds422.
Provider failure returns502 without saving a replacement, allowing the UI to retain
the answer and parent for retry. Confirming the newly reviewed plan remains a
separate `/execute` request. GET recovery works for each new task after reload.

Preparation resolves the verified JWT subject to the actual active account via
the read-only `GET /accounts/current/scope`. An uninitialized account receives
`409 ACCOUNT_SETUP_REQUIRED`; preparation never provisions it. Caller-provided
account, role and display-name hints are not authority. Resource summaries and
task-local references come from the caller's domain APIs. Context is JSON-only,
bounded in size/depth and recursively rejects credentials before any provider call.

The returned plan contains immutable `version`, `payloadDigest`, `expiresAt`,
`planningStatus` (`READY` or `NEEDS_INPUT`), human-readable `questions`, and
`executionStatus`/`execution`. Clarification creates a new reviewed task. The UI
invalidates confirmation when the task, version or signed-in identity changes.
Execution accepts `{confirmed, expectedVersion, payloadDigest}`. Legacy callers
may omit version/digest for an immutable stored task; conflicting supplied values
return 409. Expired ready tasks return 410. Tasks are scoped to both user and
actual account; another actor receives 404 from GET and execute.

PostgreSQL stores the plan, references, action statuses, stable operation keys
and audit. An atomic claim permits one executor across replicas. Statuses are
`READY`, `NEEDS_INPUT`, `RUNNING`, `SUCCESS`, `FAILED`, `PARTIAL_SUCCESS`,
`UNKNOWN` and `EXPIRED`. Interrupted execution becomes UNKNOWN after a ten-minute
lease; it never becomes READY automatically. Domain timeouts/5xx after mutation
are UNKNOWN. A status read performs no write. Reconciliation is an explicit,
confirmed execute of the same reviewed task and is permitted only for adapters
whose owning API has a durable atomic creation receipt; legacy multi-step tools
are not blindly repeated. No user JWT is stored with a task.

Default expiry is 24 hours and retention at least 30 days after expiry. Scheduled
maintenance removes expired retained tasks and cascades their references/actions/
audit. Provider-call records carry the resulting plan ID, model/provider IDs and
safe error category; no raw provider exception body is stored. A selected provider
failure yields clarification with zero actions, without switching models. No
automatic model repair is performed (zero repair calls). Existing rule-based
legacy tools remain available when no provider is configured.

For a real device, the compiler accepts one explicit name, authorized type and
optional personal location, manufacturer/model only when supplied. It compiles
one ordinary `POST /devices` with a stable `Idempotency-Key`; source credentials
and telemetry setup remain in ordinary dedicated forms. The device service
atomically commits the creation receipt, device and existing outbox events.
Conflicting payload returns 409; replay renews account/resource checks and never
recreates a decommissioned or removed resource. Receipts last 31 days. Success
links to the actual device configuration with `SOURCE_NOT_CONNECTED`.

## Dashboard creation

### Current catalogue composition (intent v3)

`creation-v6` / `dashboard-intent-v3` uses `name`, authorized `deviceRefs`, a
relative `period` and a complete ordered `widgets` list from
`context.dashboardWidgetTypes`. No template is required. Each widget has a
stable ASCII `key`, catalogue `type`, readable `title`, `width` FULL/HALF/THIRD
and integer `height`2-12. Optional fields: `deviceRefs`, registered `metric`/`unit`,
`sourceRef` (vehicle maps), catalogue `visualization`/`reducer`, note `text`, and
per-widget `period`. Raw queries, URLs, coordinates and definitions are forbidden.

`GET /dashboards/composition/widget-types` supplies current supported widget choices.
`POST /dashboards/composition/prepare` resolves authorized `deviceIds`/`sourceId`,
validates capabilities, compiles normal typed widget queries and all responsive
layouts, and returns READY with `definition`/`digest` or NEEDS_INPUT with diagnostics.
It persists no resources. Execution prepares again and checks the exact digest
before posting the immutable reviewed definition to the ordinary dashboard API.

Maps and charts may coexist in one composition. The real geographical widget is
`vehicle-map`; `location-matrix` is a table and never substitutes for it. Map sources
are resolved to registered telemetry source identities; history is limited to24h.
An explicit per-widget period permits a short map interval and longer charts.
Missing metrics/units, ambiguous sources and unsupported presentations require
clarification. Energy, tariffs, cycles and source-bound variables retain their
ordinary configuration requirements; no readings are invented.

Clarification renews refs in `previousDashboardIntent.widgets` and preserves all
unchanged choices. Old template reviews become widget preference hints and can be
extended with any supported widget. Newly compiled plans carry `compositionVersion:1`.
Legacy prepared template tasks remain executable via their original preparation
endpoint. The template-only contract below describes that compatibility path.

### Legacy template preparation

`CREATE_DASHBOARD` accepts a name, template ref/version, authorized device,
source and metric refs, a supported relative period and optional `panelLayout`.
The model never supplies raw coordinates, definitions, widget query code or ownership fields. The server fetches a bounded
personal catalogue through the current JWT and assigns task-local opaque refs.
Selected devices are limited to 20, candidates to 100 and compiled widgets to 12.
Raw browser labels, source configuration and credential fields are excluded.
The context distinguishes explicitly selected equipment (`selectedDashboardDeviceRefs`)
from catalogue candidates; the model copies selected opaque refs when the request
refers to selected equipment. Listing a candidate alone does not select it.

Dashboard-service prepares the definition with its template data, manifests
and ordinary definition/binding validators. The immutable plan records its canonical
digest and readable template/device/panel preview. Missing capabilities, ambiguous
sources or changed template versions require clarification. Templates requiring
energy counter semantics, tariffs, cycles or production periods use the ordinary
editor. A registered single source is not evidence of live connectivity. Historical
metric capability is distinct from samples in the requested window; empty data stays
no-data. Missing capabilities never become invented metrics or zero values.

Confirmed execution rechecks current scope/bindings and prepares the template again.
A changed definition digest prevents creation. Success invokes only ordinary
`POST /dashboards`, creates a private unpublished draft and links to its editor.
Publication, sharing and personal defaults require their existing separate workflows.
Device creation offers a separate dashboard request with a fresh review.

### Conversational layout intent v2

`creation-v5` uses `dashboard-intent-v2`. `panelLayout` is a complete ordered
selection of 1–12 distinct keys from the selected template's `panels` catalogue:

```json
[
  {"key":"temperature-series","width":"FULL","height":6},
  {"key":"temperature","width":"HALF","height":3},
  {"key":"incidents","width":"HALF","height":4}
]
```

Width is `FULL`, `HALF` or `THIRD`; height is an integer 2–12 grid rows. Omitted
width/height default to FULL/4. List order packs left-to-right, wrapping below
the tallest panel in a row. Desktop/tablet/mobile use 12/6/1 columns, so mobile
stacks. An omitted list retains the template defaults; an explicit list removes
omitted panels. Unknown/duplicate keys, coordinates, fractional heights, unsupported
widths and metric/panel contradictions fail preparation rather than changing intent.
Expansion across selected equipment remains bounded to 12 widgets.

For small models, follow the system prompt's panel rules: return the whole list,
copy current catalogue keys literally, preserve every unchanged panel/size and
ask when the requested panel is unsupported. No model-generated chart data is
accepted. The server derives `previousDashboardIntent` from the saved parent:
name, template, period, panel selection and selected equipment remain available
as untrusted preferences with current task-local refs. Authorization is renewed.
Each refinement produces another immutable review and canonical digest.

The independent assistant window uses one composer, in-memory conversation history
and floating/sidebar-overlay/expanded modes. Presentation preferences survive
reload; conversation text does not enter persistent browser storage. Minimize and
route changes retain the mounted conversation. A reload can recover the latest
authorized task through the history menu. The assistant route opens this same host.
Context is attached explicitly, including the selected-device dashboard handoff.
Read-only plans run after preparation; a dashboard's **Создать этот дашборд** button
is its explicit confirmation. Other writes retain the existing confirmation dialog.

The dashboard adapter hashes the full scoped stable action key into a domain key
within its 128-character limit. Confirmed UNKNOWN recovery first reads the owning
creation receipt with the current JWT. An existing receipt returns the original
draft, including after restart or template drift. Only an absent receipt permits
fresh preparation and the same keyed create; denied/unavailable receipts fail closed.
The task expires within 24 hours and the domain receipt lasts 24 hours after creation.

## Current Boundaries

The agent may call domain services only through explicit tools and service API
contracts. It does not own device, telemetry, analytics, alert, simulator, or
ML lifecycle data.

Production hardening for managed secrets, auditable tool execution, and
least-privilege service identity is target work documented in
[../../target/production-readiness.md](../../target/production-readiness.md).

### Widget capability correction (creation-v9)
Dashboard intent v3 adds optional per-widget booleans `showTitle`, `showMapDetails`
and recorded field key `vehicleField`. The planner receives allowlisted field metadata
from authorized `/telemetry/devices/{id}/fields` without values or source configuration.
It can compose `vehicle-value` (number/text/boolean) and `vehicle-timeline` (recorded
state history) with maps and numeric charts. Missing next-stop fields must not be
replaced with static notes pretending to be live widgets. Unsupported widget fields
trigger one same-model correction with the rejected draft and allowed keys; compiler,
authorization, preparation and immutable execution checks remain authoritative.

### Incremental dashboard review (creation-v15)
Initial dashboard preparation accepts a complete widgets list. For a prior server-owned
composition, the model instead emits widgetEdits (existing key plus only changed
fields), addWidgets (complete new widgets), and optional widgetOrder (all remaining
keys exactly once). Explicit {key,remove:true} removes a widget; omission retains it.
Root name/period/deviceRefs are inherited unless supplied. The merged full intent
passes the same authorization, preparation and immutable digest checks. Full widget
replacement on refinement receives one same-model repair; repeated malformed output
does not overwrite the current review. Prior structured suggestedValues are passed
through a naming clarification so an acceptance reply is not itself used as a title.

Plan responses add previousReviewIds in chronological order, limited to owner-scoped
eligible unexecuted dashboard ancestors. GET /ai-agent/plans/{id} revalidates selected
access and expiry as before. Browser Back/Forward/version selection only fetches a
review; it never plans or executes it, and subsequent refinements branch from that
selected plan. Recovery rebuilds ancestor navigation from the server response.
A model-selected {restoreSteps:1} is accepted only during clarification: the server
selects a stored ancestor, revalidates its refs and clones its exact frozen action
into a fresh reviewed task. Arbitrary model history IDs and definitions are not used.
After execution, history controls are locked; returning to a review does not undo an
already-created dashboard. Ordinary explicit creation confirmation remains required.


Compact card refinement: widget fields accept boolean `compact`; `height` accepts
integers1–12. For a one-row value/status card the planner emits `height:1,compact:true`.
Omitted presentation choices remain preserved through task renewal and edits.

Map presentation accepts boolean showMapNotices independently of showMapDetails. To hide stale/truncated footer text, edit the map with showMapNotices:false. Omitted values default to visible and remain preserved in subsequent edits.


Dashboard preparation failures trigger at most two same-model repair calls with
actual preparation diagnostics and rejected parameters. These attempts do not
execute or publish a dashboard. Explicit naming commands outrank suggested names
and known names are persisted even when preparation fails. Server task memory
retains originalDashboardRequest and pendingDashboardChange independently of the
latest message; a pending failed change cannot be acknowledged by empty edits.
Failed repairs retain the previous review, name and pending change for followup.
In widgetEdits, explicit null clears only optional metric/unit/reducer/vehicleField/
sourceRef/visualization/text/period fields; omission still preserves them. This
allows type changes to remove obsolete incompatible parameters without rebuilding
other widgets. Vehicle-timeline accepts visualization=line for recorded numeric or
boolean fields and list for timestamped rows; fields are not fabricated metrics.

Dashboard creation uses a dedicated one-tool prompt; refinement has a separate
latest-change prompt. Current source values must remain scalar while history
uses separate charts. Redundant RAW metric/unit parameters on vehicle-value or
vehicle-timeline are normalized into the same recorded vehicleField only when
that field and unit match the selected device catalogue; unknown fields, unit
conversions and aggregates still require validation/repair.
Partial reorder supports moveWidgets:{keys:[existing keys],position:START|END|BEFORE|AFTER,anchor?:existing key}.
BEFORE/AFTER require a nonmoving anchor. The server retains every other widget's
relative order. moveWidgets and widgetOrder cannot be combined in one edit.

Incomplete composition actions cannot fall through to legacy template compilation
when dashboardWidgetTypes is present. Empty refinement parameters/edit lists trigger
same-model schema repair, even without a previous failure. addWidgets rejects a
redundant field/metric view with the same type, binding selection, period and
visualization as an existing widget; repair must reuse/edit the existing key.
Different data, periods or visualizations remain distinct supported views.


## Widget capability-driven presentation (2026-10-23)
Dashboard widget types now carry a compact projection of dashboard-owned capabilities:
accepted value types, visualization-specific value types, binding requirements and
supported setting types/defaults/enum choices. The full ordinary manifest retains
editor labels/groups. AI receives plain bounded values, not raw JSON nodes, and the
input guard remains enabled. Descriptors are not duplicated inside each widget.

Initial panels and widgetEdits accept `settings:{key:value}`. Supported keys are
listed per type in the catalogue. showTitle/showDetails are common; map controls,
state labels, compact values and prediction details are type-specific. Settings
merge by key; omission preserves prior values. Explicit null resets a key and is
retained through later turns. A typed edit supersedes its saved flattened legacy
equivalent. Repeating a key in both formats in one edit is rejected. Creation lowers
settings through the owning dashboard service; the model cannot submit raw query,
binding or definition objects. These capability changes do not introduce semantic
operations or claim that the complete tram refinement replay has passed.


## Durable dashboard working tasks

Dashboard conversations now write typed working state separately from immutable
ai_reviewed_plans. This does not change execution confirmation, review digests,
receipts or device-creation behavior. Migration V003 adds ai_dashboard_tasks and
ai_dashboard_turns; it does not rewrite existing reviewed plans.

POST /ai-agent/plans accepts optional requestId, taskId, branchId,
expectedTaskRevision and baseReviewId alongside its existing fields. New clients
allocate a root taskId before sending, set branchId equal to it and revision0;
this makes initial-failure recovery possible even if no response arrives.
Once a task exists without a valid review, continue through POST plans using its
taskId/branchId/current revision. Task kind is server-owned; a short reply does
not need to repeat a dashboard keyword. Stable model selection is retained.

POST /ai-agent/plans/{planId}/clarifications accepts those optional fields plus
acceptedSuggestion (defaultfalse). baseReviewId must match the selected planId.
acceptedSuggestion explicitly accepts the structured prior suggestion; the normal
chat sets it only for the existing labelled automatic reply, not for arbitrary
text that happens to resemble agreement. User naming commands take precedence.
Explicit name facts carry user provenance and are checkpointed before preparation.
Model suggestions alone are not accepted task facts. Legacy name parsing remains
a bounded compatibility input, not a growing catalogue of new phrase handlers.

New-protocol requests use a stable requestId and expectedTaskRevision. The same
scoped identity/payload returns the same immutable review/digest without another
planning sequence; current execution status can still reflect a later execution.
An in-progress duplicate returns409 and can be recovered without resubmission.
A completed failure replays its recorded HTTP failure; use a new requestId for
an intentional new planning attempt. Changed payload with the same identity,
stale revision, active branch write or invalid base returns409. No DB transaction
remains open during model/catalogue HTTP calls.

Plan responses add nullable task: taskId, branchId, revision, kind=DASHBOARD,
originalRequest, confirmed name, lastAcceptedReviewId, status, failureCategory,
expiresAt, selectionStatus and bounded pending changes. Existing plan IDs,
version/payloadDigest/previousReviewIds and execution endpoints remain authoritative
for exact reviews. GET plans/{id} exposes that review's working-state snapshot;
GET task recovery exposes the current branch state. Neither invokes a model.

GET /ai-agent/dashboard-tasks/{taskId}?branchId={branchId} returns
{task,lastReview}; omitted branchId selects the root. lastReview is null when no
valid candidate exists or current resource access prevents showing it. The safe
summary excludes context_json, model refs, credentials and internal diagnostics.
Resource selections are preferences: they are reauthorized, and unavailable
ones are labelled UNAVAILABLE without granting access. Foreign actor/account404;
expired task410. Pending/failed turns keep the original request and confirmed facts,
advance the ledger revision and leave the last valid review unchanged.

Selecting an older exact review is read-only. A new continuation from an older
accepted review forks its stored state; its new branchId is returned. Concurrent
revisioned continuations against one current branch cannot silently overwrite.
Legacy callers omitting metadata retain immutable-parent branch behavior. They
cannot obtain network retry deduplication without supplying requestId. A first
legacy continuation imports only bounded existing selections and grounded user
facts; executed/RUNNING/UNKNOWN reviews are not reopened for editing. A recorded
duplicate is a read of an already accepted outcome, not a new terminal-plan edit.

Prepared candidates do not by themselves prove pending goals APPLIED. Pending
work remains visible until outcome verification or explicit supersession; an
empty or failed candidate cannot clear it. Current limits remain8000 initial
characters,2000 reply characters and30 continuations. Pending model context is
bounded; oversize work returns422 without deleting accepted state.

Historical selection recovery keeps only task, branch and selected review IDs in
subject-scoped browser storage. Recovery reauthorizes the current task and then
GETs the selected immutable review, using that review's fact/revision snapshot for
an explicit continuation. Reload must not silently replace it with the branch tip.
A new turn clears that local selection pointer. No names or definitions are copied
to browser task storage. An earlier NEEDS_INPUT naming question remains on the
current branch after a failed answer; only an older accepted dashboard review
forks. This preserves a confirmed name even before the first valid preview.

A confirmed name is authoritative input to ordinary dashboard compilation, even
when a model candidate omits it or repeats the older name. Exact historical restore
instead uses the owner-authorized stored definition and fact snapshot. The optional
restoredFromReviewId response field comes from the server-owned history restore,
not model prose; later pending changes are superseded rather than marked applied.
### Data references for composition

Dashboard planning now renews the dashboard-owned capability response for selected
devices before invoking a model. `dashboardDataChoices` exposes task-local dataRef,
deviceRef, actual labels/types/units, source label/ref and compatible views. The
model chooses dataRef plus a supported widget/view rather than constructing source
field versus registered-metric arguments. The scoped server mapping retains exact
stable identities and the metadata snapshot; refs themselves are never permissions.
A stale/foreign ref or conflicting raw identity is rejected. Presentation-only
changes preserve their existing data binding. Explicit rebinding clears obsolete
data arguments before resolving the new exact choice.

The model context is capped at 60 resolved choices and retains its existing depth,
item and byte guards. Large selections must be narrowed; they are not truncated.
An ambiguous source choice is blocked until a structured sourceId selection or an
exact reply to a structured sourceId question selects an offered public source
label. The confirmed source is checkpointed in durable task selection before the
provider call. Such source questions require explicit choice and have no automatic
default timer. Replies to unrelated questions cannot select a source. No transport-specific
field alias table or inferred unit is added. Legacy metric-shaped vehicle arguments
are adapted only when the current resolved catalogue has one exact field match.
Resolved data refs are short per-turn namespaced identifiers, not long resource
UUIDs. Their scoped mapping includes the stable `choiceId` and metadata snapshot.
The compiler supplies optional `dataChoiceId` to composition preparation from that
mapping; model panels cannot author this field. Preparation checks the exact
current identity, including declared type/unit, so a number-to-boolean change does
not pass merely because both types support line. Existing callers may omit it.

AI source preferences use additive `context.sourceSelections:{deviceId:sourceId}`
for multiple selected devices; legacy `sourceId` remains supported for one device.
Selections are stored per device in durable task state. Choosing one source cannot
filter another vehicle's choices or erase its prior confirmed selection. An exact
source-label reply to a structured question is grounded against scoped stored
metadata before checkpointing; an unstructured question can accept such a reply
only when the server retained source ambiguity and an already confirmed name.
No question-text classifier or transport-specific alias catalogue is involved.

A real unresolved source choice becomes a structured manual question instead of
consuming the composition repair budget. `clarification.requiresExplicitChoice`
is server-derived for sourceId/sourceRef questions and disables automatic defaults.
Text histories prepared as lists include an explicit explanation that numeric lines
are unavailable for text, derived from the actual selected data type.
Source choices show a per-device short sourceChoiceLabel (for example Source 2,
localized for the user) alongside the actual public sourceLabel. The control label
is scoped metadata, not a physical source name. Labels stay stable after filtering
a selected source. Unstructured no-action questions may display factual available
source options only when the server has unresolved ambiguity and a confirmed name.
Provider/preparation failures are not rewritten as source questions.

## Opt-in semantic dashboard planning (2026-10-24)

`AI_DASHBOARD_OPERATIONS_ENABLED=false` is the default. With this server flag set,
new model creation/refinement uses `dashboard-operations-v1` through the existing
`CREATE_DASHBOARD` planning action. The HTTP plan/clarification/confirmation routes
are unchanged. This flag controls generation, not execution of stored reviews;
rollback retains template/composition readers and their immutable definitions.
Default enable still depends on outcome verification and final repair/evaluation.

The envelope contains exactly `protocol` and `operations` (1–24). Every operation
has a unique short `id` and discriminator `op`. Its writable arguments are:

| op | Arguments after id |
| --- | --- |
| show_map, show_current | current authorized dataRef; optional existing widgetKey |
| show_history | dataRef; optional widgetKey, compatible view and relative period |
| add_note | title, text (up to 1000 characters) |
| place_widgets | targets, TOP/BOTTOM/BEFORE/AFTER position; anchor for BEFORE/AFTER |
| resize_widgets | targets; FULL/HALF/THIRD width and/or height 1–12 or heightPercent 1–400 |
| set_presentation | targets; settings list of `{key,value}`; null resets exactly one setting |
| rename_widget | widgetKey, title (up to 120 characters) |
| set_data | widgetKey, dataRef; retains role/type |
| remove_widgets | exact targets |
| rename_dashboard | explicit/accepted name (up to 80 characters) |
| set_period | supported relative period; optional targets, empty means dashboard default |
| restore_review | restoreSteps, alone in the envelope; authorized saved ancestry resolves it |
| keep_review | no arguments; alone, retains the exact selected READY review after current authorization checks |

Targets resolve only to selected-review keys or `@id` of an earlier show/add
operation. No forward/cyclic/foreign/removed refs, duplicate IDs/targets, raw queries,
coordinates, definitions, binding objects or full ordering are accepted. ALL is
valid only as the sole presentation target and requires support on every widget.
Settings are checked against current dashboard-owned descriptors before preparation.
Conflicting writes to the same property fail instead of relying on operation order.

Show operations uniquely reuse the same role/data/period/view. A different history
view is a separate widget unless an explicit widgetKey requests conversion of an
existing history. Current values never consume history widgets. Ambiguous
matches require an explicit target. New widget keys derive from persisted turn ID
and operation ID, so repair/retry keeps their identity. New current cards default
to compact THIRD-width, one-row cards. The server expands data choices into current
authorized refs; it never treats model resource labels as access grants.

`rename_widget.widgetKey` also accepts `@id` of an earlier show/add result,
including on initial creation. For example, show_current with id `speed` followed
by rename_widget with widgetKey `@speed` assigns a user-requested card title.
Other show/set_data widgetKey arguments are selected-review keys only.
Cross-device set_data includes the resolved authorized device in dashboard scope
while preserving existing selections and unrelated widget bindings.

keep_review requires an existing selected READY parent and retains the pending ledger;
it cannot be mixed with edits or used on initial creation. It copies the exact
saved preparation/definition through the authorized review-history boundary,
without recomposition or execution. It does not apply or supersede pending goals,
and does not trigger task-state restoration. Restore remains bounded to1-30 ancestors.
Strict provider schema enumerates positions, widths, history views and periods,
and bounds row heights, percentages and titles; server validation remains
authoritative for every provider. Old stored v1 reviews retain their payloads.

The compiler lowers into ordinary composition preparation. Server-derived
`operationChanges`, `expectedOutcomes`, `pendingChangeIds` and `responsiveReflow`
are stored alongside the existing prepareIntent/digest/definition. They describe
compiler intent; the separate outcome-verification stage must still check actual
postconditions before claiming full semantic acceptance. Presentation/title-only
edits permit no layout change. Placement removes/reinserts just the selected group
and preserves all other relative order; ordinary packing may shift rows at desktop,
tablet and mobile breakpoints. Heights round to existing grid rows and clamp to1–12;
the review reports this quantization. Arbitrary editor geometry cannot be imported
through this bounded protocol. Legacy template refinements without a composition
base are rejected; legacy confirmations still execute their stored payload.

`DashboardOperationSchema` generates the JSON Schema from the sealed wire records.
Compatibility operation profiles advertising supportsJson receive Spring AI native JSON Schema;
all provider responses pass the same strict server parser/compiler. Optional fields
can be omitted locally or null in strict provider output. Failed operations never
fall back to full raw recomposition. Restoration uses the existing authorized
immutable history boundary and can be followed by an ordinary later edit.

For opt-in planning, unused legacy templates and duplicate per-device metric/field
summary lists are removed before the existing input budget is checked. Current
`dashboardDataChoices`, selected review state, source/device identities and
credential scanning remain authoritative; input limits are not raised. Legacy
generation retains its existing catalogue projection. An absent visualization in
a legacy vehicle-timeline means list; time-series defaults to line.

## Optional private knowledge for alert-rule preparation

With `app.ai.knowledge.enabled=true`, a conversation message can specify
`context.knowledgeCorpusRefs` as an array of up to 32 collection UUIDs within
the existing turn selection limit. The server checks the authenticated user's
current collection grants and records the selected scope in the plan's
requirements journal. The alert-rule skill receives the collection IDs and
requirements revision in `knowledge.search.scope`.
Omitting `knowledgeCorpusRefs` on a continuation reuses the plan's selection
after checking current grants. An explicit empty array clears the selection
and advances the requirements revision when the scope changes.

The skill can request `knowledge.search` for that scope, then request
`knowledge.resolve` in a subsequent exchange using the returned snapshot and
up to eight chunk IDs. Search returns IDs and source versions. Resolve checks
current grants, versions and requirements before loading text. The runtime
rechecks access before model calls that carry the text.

Search is lexical by default. Deployments that provision the optional pgvector
SQL and set `app.ai.knowledge.vector-generation` use hybrid search with the
configured deployment embedding model and the existing per-turn budget.
The feature is disabled by default and requires a PostgreSQL role subject to
row-level security. Retrieved text is evidence for preparation. Existing review
and confirmation govern rule execution.

## A5 skill/context/output stabilization — 2026-10-28

The primary runtime now selects output mode from the selected configuration:
`supportsJson=true` plus `responseFormat=json_schema` selects `STRICT_JSON`;
`supportsTools=true` plus `responseFormat=tool` selects `NATIVE_TOOLS`.
The legacy null/empty, `text` and `json_object` formats explicitly retain
`JSON_TEXT` compatibility. Inconsistent declarations fail with
`OUTPUT_CAPABILITY_UNSUPPORTED`. No model-name heuristic, provider probe,
error-triggered model switch or output-mode downgrade is performed. This does
not change any saved provider configuration or deployment default.

`strict-projection-v1` derives a provider wire schema from the registered canonical
response and the latest workflow operation schema. Its root is `{proposal: ...}`;
canonical discriminated branches use `anyOf`, optional properties are required
nullable wire properties, and unconstrained JSON fields are JSON-encoded strings.
Normalization decodes those fields once and removes only absent optional nulls.
Canonical required nulls remain. Conditional/range/format constraints stay in the
canonical server validator; strict wire output is never sufficient acceptance.
Schema rejection, refusal, incomplete output, malformed wire JSON and unexpected
tool calls are typed failures, with actual usage retained under A2. The same
selected model and mode apply to every exchange. Live provider compatibility and
semantic quality remain separate qualification requirements.

Primary skill instructions are package v2 with stable instruction digests; the
existing command/manifest contract remains version 1 for compatibility.
Sections cover purpose, inputs, context, operations, constraints, questions,
response schema, examples and acceptance. Selection uses registered intent.
`review.base.actions[].currentWidgetInventory` projects the exact accepted
compiled definition: keys, structural current/history/map roles, bindings,
queries, presentation/settings, sizes and visual order at every breakpoint.
The exact current message, mandatory history, pending question/options, confirmed
facts and accepted review remain under the existing A4 bounds and access checks.

The explicit service profile `app.ai.orchestration.profile=FIRST_PASS_DIAGNOSTIC`
(or `APP_AI_ORCHESTRATION_PROFILE=FIRST_PASS_DIAGNOSTIC`) disables automatic
format and domain-preparation corrections. It has no model judge. `STANDARD`
retains the previous behavior; no default is enabled by this change. Profile,
mode and projection version are included in the policy fingerprint, requiring a
new conversation when they change. Schema, access, immutable review,
confirmation, receipt and existing structural guards are always active.
Necessary planner/step/context rounds all use A2; first pass is not one HTTP call.
Model traces record output mode and projection version alongside body/schema,
context and policy versions already held by the shared ledger.

Repository fixture `evaluation/a5-dashboard-failures-v1.json` retains the frozen
live-12 score (1/5 turns, 0/2 dialogues) and sanitized accepted/rejected definitions.
Its stage diagnoses distinguish observed failures from unknown internal causes.
First family: preparation rejection, then NEEDS_INPUT/FAILED continuations.
Second family: creation accepted; READY edit failed current speed/doors top
placement and untouched order on all three breakpoints. Independent offline
assertions retain these failures; generator masks are not their oracle.

Diagnostic checkpoint predeclaration: require 100% of frozen original-family
and held-out critical requested effects, zero unauthorized/confirmation breaches,
zero collateral binding/settings/order changes. Preserve every failure and
unreached turn in denominators. Current A2 ceilings remain 6 calls, 700,000 input
and 72,000 output tokens, 240 seconds and USD 0.60 reserved upper bound per turn;
60 calls, 7,000,000 input/720,000 output tokens, 3,600 seconds and USD 6 per parent
plan. Live campaigns need an additional declared aggregate ceiling; these are
limits, not measured costs. No diagnostic live result is established by offline
fixtures or historical corrected outcomes. Full task 2.3 remains open until core
workflow completion and authorized first-pass checkpoint evidence.

## A5 separate source and persisted-draft slice — 2026-10-28

The registry now also declares `UPDATE_DASHBOARD_DRAFT` (14 registered intents).
It uses the existing dashboard operations schema/compiler/composition and shared
preview, then the owning `PUT /dashboards/{id}/draft` with reviewed etag and
baseRevision. The server obtains dashboardId from an actual successful receipt,
loads the current authorized draft, and rechecks its version before saving the
new immutable review. Unexecuted create reviews now use REFINE_DASHBOARD_REVIEW
(see the later responsibility contract below).
CREATE_DASHBOARD refuses to substitute a copy for an executed dashboard edit.
UNKNOWN updates reconcile against the owning draft receipt and never replay a
creation. UI confirmation explicitly says update and submits the exact review.

Current limit: this slice supports a persisted dashboard whose current definition
matches the executed AI review, and refinements of its pending update review.
An external editor change is rejected with
DASHBOARD_BASE_CHANGED_REVIEW_CURRENT_DRAFT / DASHBOARD_DRAFT_VERSION_CHANGED;
importing an arbitrary independently-edited draft into semantic composition and
update undo/history still need acceptance. Task 1.5 is not complete merely
because the versioned execution path exists.

New v2 CREATE_DATA_SOURCE proposals describe only the installed HSL_HFP connector:
deviceId, provider=HSL_HFP, mode=bus|tram, vehicleKey. Current enabled capability,
VEHICLE type/status and authorization are checked. DeviceSetupExecutor delegates
a separate confirmed source step to the same HslDeviceIntegration port with the
stable action key. It never creates another device. Connector reservation/replay
and binding receipts retain their existing ownership semantics. Immutable legacy
composite HSL device reviews and generic source reviews keep their execution
paths. Legacy generic/simulator-source NL parity needs separate migration evidence;
it is not silently counted as qualified v2 functionality.

`GET /ai-agent/orchestration/profile` is an authenticated read-only profile
inspection; it does not resolve/call a model. The checkpoint harness requires
FIRST_PASS_DIAGNOSTIC, corrections=false and modelJudge=false before every turn.
`ai-first-pass-checkpoint.py` freezes the original two families and two existing
held-out paraphrases (one trial each, nine turns), unchanged independent predicates,
100% checkpoint threshold and aggregate caps before paid calls. Campaign caps:
40 calls, 7M estimated input tokens, 720k reserved output tokens, USD4 reserved
upper bound and20 minutes. It reserves room for a complete A2 turn before calling,
stops rather than extending a ceiling, preserves failed/unreached turns and writes
into a new artifacts directory only. Provider settings are never rewritten.
Call purposes distinguish PRIMARY/CONTEXT_ACQUISITION from FORMAT_CORRECTION/
DOMAIN_RECOVERY; multi-call first-pass success is distinct from recovered/final
success. Untyped historical calls remain unknown; no historical report is rescored.
The actual cost remains unknown without a comparable price snapshot.

## A5 dashboard skill responsibility contract — 2026-10-28 follow-up

There are now 15 model-facing intents and 14 existing execution tools.
Dashboard command/output/skill schemas use version 2; other skills retain version 1.
The internal operation records remain dashboard-operations-v1.

| Skill | Base | Model response | Execution |
| --- | --- | --- | --- |
| CREATE_DASHBOARD | New composition; no inherited dashboard review | No widgetKey fields; exactly one rename_dashboard; dataRef + local operation ids and backward @id references | Existing reviewed create |
| REFINE_DASHBOARD_REVIEW | Selected READY/SUPERSEDED unexecuted create review | Exact inventory keys for existing widgets; null show-widget key only for adding/ensuring a role; @id for earlier additions | Existing reviewed create, only after a new confirmation |
| UPDATE_DASHBOARD_DRAFT | Actual executed dashboard or its pending update review | Same exact-key edit protocol; no keep/restore of executed drafts | Same dashboardId with current etag/baseRevision and owning receipt |

Creation rename_widget uses target=@id. After schema/reference validation the
server maps target to the existing internal widgetKey and inserts null keys on
creation show operations. This projection makes no semantic guesses. An unknown
edit key is rejected by snapshot membership even if it looks like a valid ID;
it never silently becomes an addition. The existing compiler still enforces
role compatibility, settings, removal conflicts and layout/preservation rules.

Planner inputKeys are only declared resource identifiers (including deviceIds).
Name, period and widgetKeys are forbidden planner parameters. The selected
domain skill interprets these from the exact message and retained dialogue.
Edit skills retain widgetKeys as a declared clarification field; creation has
no such field. Neither permits widgetKeys in planner inputs.
planSummary.steps now includes dependsOn and goalIds; older documents without
those optional fields still round-trip. A failed-step replacement must preserve
actual prerequisites and goals and must not depend on the superseded failure.
Replacing a step retains its accepted review reference for context and preview,
while invalidating the replaced executable review binding.

For dashboard workflow context, an explicit plan binding of `deviceIds` or
`deviceId` determines which device capabilities are loaded. A context request
can narrow the selection when the plan leaves it unbound. The owner reads still
check access and supply the actual device, source and field capabilities.
For a newly connected source, the dashboard step can bind `sourceId` to the
connection receipt's `dataSourceId`. An enabled source listed for the selected
device remains selected while its field catalogue is empty. The assistant only
offers widgets supported by the available capabilities.

For a saved draft, `restore_review` selects a previous successfully saved
version of that same dashboard from the assistant review history. Preparation
copies the stored definition into a new review and checks its resource access.
Confirmation uses the current draft revision and ETag to save to the same
dashboard.

Dashboard skills advertise only device.catalogue, workflow.context and
conversation.history. The provider request includes the selected dynamic
operation schema once rather than repeating static/dynamic command schemas.
Mandatory dialogue, pending question/options, receipt-backed facts, resolved
bindings and the accepted edit inventory remain present. Initial creation
excludes an ambient review.base; explicit dashboard base selection must use an
edit skill. A successful device review does not block creation.

Skill instructions and planner guide versions enter the pinned policy digest.
An active plan created with an earlier policy cannot silently switch contracts;
start a new plan/conversation for generation with the new package. Reading and
executing immutable saved reviews continues through the existing payload path.

All examples and rejection cases are checked offline, including lowering through
the existing compiler with independent current/map/history order assertions.
This does not prove improved model interpretation. Frozen live-12 remains
1/5 turns and 0/2 dialogues; the failed linked diagnostic campaigns retain their
original scores. V/R, full workflow/UI acceptance and A6 remain open. No new
paid campaign, provider change, defaults enable or archive belongs to this slice.

Validation:188 AI-service offline tests passed with the three Docker-backed
test classes excluded; after the final edit-clarification field adjustment,
12 targeted registry/schema/context tests passed. Strict OpenSpec and project
structure checks passed. No browser or real-provider quality acceptance was run.

Subsequent explicitly authorized live verification (campaign03,2026-10-28)
failed:1/9 turns,0/4 full dialogues. The first review used version2 creation,
but a separate existing-role-predicate audit found current:doors missing.
Its permissive first-turn pass remains unchanged in the frozen report.
The next user edit failed before a model call with
DASHBOARD_BASE_REQUIRES_EDIT_SKILL: IntentOrchestration.submit re-prepares a sole
WAITING_CONFIRMATION step under CREATE_DASHBOARD instead of routing the new
request to REFINE_DASHBOARD_REVIEW. Accepted review preview was retained.
The diagnostic harness then halted7 remaining turns as
DIAGNOSTIC_ACCOUNTING_INCOMPLETE because the pre-model rejection produced zero
calls. They remain unsuccessful trials; their model interpretation is untested.
This exposes incomplete runtime responsibility separation despite the passed
offline contracts; A5 task2.4 is reopened.

Exact-turn A2 ledger:2 model calls,15,812 input,6,697 output (6,016 reasoning
included),69,417ms combined model latency, zero correction/recovery calls.
USD0.20 is the charged/reserved upper bound; actual cost remains unknown without
a price snapshot. No dashboard mutation occurred; owned fixture cleanup succeeded.
STANDARD was restored and model settings verified unchanged. This versioned
development reevaluation does not replace historical live-12 or qualify A6.
Evidence: artifacts/a5-verification-20261028-03/report.md and its immutable
checkpoint/summary.json, analysis.json and usage-ledger-evidence.json.

## A5 data-driven design and optional assessment (2026-10-28)

Dashboard instructions v5 include dashboard-design-v1: explicit user metrics, representations, names, periods, order and sizes are constraints; the model chooses only unspecified details. Fully delegated design selects useful information and supported views from actual number/string/boolean types, supplied formats/units, device purpose, freshness and capabilities. No device-specific template, required metric bundle, separate semantic preprocessing or extra layout model stage is introduced. Multiple coherent designs may satisfy a delegated request. Model-selected size/order/presentation still lower through existing compilers and ordinary shared rendering. Pixel-level readability/aesthetics requires browser evidence.

New follow-up edits always route through planner; direct skill continuation is reserved for an identified pending-step-question answer. The accepted review/receipt resources are revalidated before model calls. Planner guidance explicitly routes unexecuted dashboard changes to REFINE_DASHBOARD_REVIEW through AMEND, preserving source-linked goals and actual dependencies.

Profiles are explicit: STANDARD retains its prior correction policy and no model judge; FIRST_PASS_DIAGNOSTIC has no automatic corrections or judge; opt-in VERIFIED adds read-only compiled-result assessment before review save. All calls use A2's existing pinned model, turn/plan budgets and capability-selected output mode. The additive assessmentResponse returns digest, verdict (SATISFIED/REJECTED/UNCERTAIN) and bounded checks with exact source message/quote and widget evidence. The VERIFY phase is recorded as OUTCOME_ASSESSMENT, separately from correction/recovery. Assessor format failure has no automatic correction; concrete outcome rejection may use the existing single domain-repair allowance. Uncertainty, invalid source evidence or changed/cancelled state cannot become READY. Access and optimistic draft version are rechecked before saving.

Assessment wire input contains exact source dialogue, accepted definition/inventory, capabilities and actual candidate; writer goals, masks and explanation are omitted. It is an independent assessment pass over original evidence, not a statistically independent model or calibrated oracle. Structural verification is always mandatory; model assessment cannot overrule it. Existing no-op/sidecar, recovery/UI and calibration acceptance remain open. Defaults/provider settings are unchanged.

The harness now distinguishes evidenced zero-call pre-model rejection from missing/uncertain accounting, retains accounting for failed turns, and never labels zero-call output a generation success. The frozen corpus remains unchanged; dashboard-design-ru-v1 adds separate delegated/mixed cases. Delegated cases have no prescribed widget count beyond existing technical limits; explicit onlyRoles assertions reject unrelated content. Optional VERIFIED runs share a predeclared remaining campaign ceiling, never promote retries or replace earlier first-pass results.

Campaign04 outcome: FIRST_PASS_DIAGNOSTIC3/11 turns,1/6 dialogues; its frozen four families3/9,1/4. Linked VERIFIED0/5,0/3; two final turns stopped on the shared time reservation ceiling. All failed/stopped trials remain counted, no rescore/retry promotion. One complete create-to-refine dialogue passed. Remaining errors include planner schema/skill selection, omitted requested roles, preservation/settings, and invalid assessor source evidence.

The added mixed-design v1 period predicate was wrong for a history-only request: actual history used LAST_6_HOURS while the dashboard default stayed LAST_24_HOURS. Original FAIL reports remain immutable. Separate dashboard-design-ru-v2/historyPeriods predicates correct the scope for future runs and test wrong/missing periods; no paid v2 rerun was made. Both cohorts had a correct two-view candidate, and VERIFIED accepted one. This does not establish a calibrated assessment false-accept/reject rate.

All28 calls are in A2:246,885 input tokens (24,960 cached),96,996 output tokens (87,360 reasoning included),810,467ms model latency. Charged/reserved upper bound USD2.80; actual cost unknown. Shared campaign ceiling40 calls/20 minutes/USD4 was respected. There were two assessment calls, one failed format correction and no domain recovery. STANDARD and unchanged model settings were verified after rollback; no dashboard mutation or new defaults. Desktop/mobile preview evidence is partial and technical metric titles still need improvement. Evidence: artifacts/a5-design-verification-20261028-04/report.md. Engineering tasks2.4/2.5 are checked; quality2.3 and full V/R/A3/A6 remain open.


## A5 compact model context (2026-10-28)

Alert rule proposals use the owning service's `ruleType` and
`conditionType` values. A device selected for a rule is stored as the owner
scope. Updates that omit a device preserve the current scope. The selected
rule and device references are checked again at execution.

Optional widget catalogue embeddings run during context preparation, before
prompt serialization. Index and query exchanges each reserve their own attempt
in the current plan/turn ledger. The deployment embedding adapter exposes no
token or monetary usage, so accounting retains the reserved bound. If an
embedding reservation is denied, lexical ranking remains available and the
complete owner catalogue stays in context.

The wire-only `primary-prompt-v1` projection is pinned in the routing policy
fingerprint. Canonical request/schema validation and stored reviewed payloads
remain unchanged. JSON_TEXT sends the selected response schema once. Native
STRICT_JSON/NATIVE_TOOLS send the projected provider schema without also embedding
the canonical schema in prose. No output mode or model is changed by this work.

Loader argument schemas, input/question rules, bindings and resolved inputs stay
available; loader result schemas and duplicated trust text are omitted. Repeated
identical nonempty choice `views` arrays use local `dashboardViewSets` entries and
`viewsRef`. This does not remove any metric, supported view, unit or status.

Dashboard bases retain the entire compiled `definition`, including settings,
navigation, payload and layouts. `currentWidgetInventory` retains keys, roles,
semantic sizes and breakpoint order; other properties are joined by widget id
from the definition. Historical generation parameters/masks/expected outcomes
are omitted. `previousDashboardIntent` is omitted only alongside the matching
accepted review. Fresh draft revision/etag, message, mandatory history, question
and options, confirmed facts and capabilities are preserved.

Verification:195 AI-service offline tests passed (three Docker-backed test
classes excluded), including lossless catalogue expansion, preserved arbitrary
widget properties, unmatched/non-dashboard review safety, canonical immutability
and all three output modes. An offline same-request reconstruction measured
59,280 -> 48,351 UTF-8 wire bytes (-18.4%); instructions28,329 ->21,441 and
input24,474 ->21,951 bytes. This is one reconstructed creation example, not a
live token measurement or quality result. Evidence is in
artifacts/a5-prompt-projection-20261028/measurement.json. No model calls occurred;
existing failed campaign scores and all outstanding acceptance gates remain.


Authorized live campaign05 (2026-10-28) used STANDARD: automatic corrections ON,
model judge OFF, at the user's clarification before any provider calls. Harness
v4 explicitly validates this profile/flags, rejects hidden assessment and retains
the existing shared A2/campaign ceilings.52 offline harness checks passed.

Real-model checkpoint: first-pass4/11 steps (36.4%); corrections recovered3/7
initial unsuccessful steps (42.9%); final7/11 (63.6%),4/6 full dialogues. The
all-predicates/all-dialogues checkpoint FAILED. Original two families remain1/5
steps and0/2 dialogues in this new sample too. Frozen four: first-pass2/9,
final5/9,2/4 dialogues. Every failed step stays counted; prior reports remain
immutable. This single development reevaluation is not independent A6 release
qualification and profiles/oracle versions are not directly comparable.

The initial tram candidate duplicated an operation id, then corrected successfully;
a separate no-rescore audit found all six requested roles in the accepted result.
Rename planner unnecessarily asked tramName and did not dispatch the edit skill.
Its subsequent move produced INVALID_JSON twice. Three-card creation failed
planner identifier format twice; dependent move had no accepted base and was
correctly rejected. Compact create/edit both needed format correction and passed.
Map/footer and delegated/exact design cases passed without correction.

A2 durable ledger:24 calls (19 primary,5 format corrections),187,005 input tokens
(24,320 cached),84,155 output (75,200 reasoning included),784,306ms model latency;
806s campaign wall time. All usage is present,0 judge calls and0 domain/outcome
recovery calls. USD2.40 charged/reserved bound is not actual billed cost; actual
cost remains UNKNOWN_UNPRICED.40-call/20-minute/USD4 reserved caps were retained.
No dashboard mutation occurred and all six owned fixture cleanups passed.

Mounted compiled classes were checked before the run; service source stayed
unchanged throughout; STANDARD and model/provider settings were retained. Real
provider calls used control telemetry ingested through ordinary stack APIs, not
physical-vehicle or browser acceptance. Evidence:
artifacts/a5-recovery-verification-20261028-05/report.md, analysis.json,
standard/summary.json and usage-ledger-evidence.json. A5 task2.3 and remaining
V/R/A3/UI/A6 gates remain open; no archive/default enable or old-score rescore.
Next: planner identity/output and unnecessary-question routing, then step format
reliability. Context reduction alone has not established sufficient quality.


## A5 planner reference and routing fix (2026-10-28)

The primary model-facing planner contract is `planner-references-v1`, with
`planner-routing-v4` guidance. A resource input is
`{"field":"deviceId","binding":{"kind":"resource_refs","refs":["resource1"]}}`.
References are local to the current authorized resourceChoices snapshot. The
server lowers them to canonical literal resource bindings; deviceIds accepts
multiple device references, scalar fields exactly one. Unknown references,
resource-kind mismatches and model-supplied literal UUIDs are rejected. Existing
fact_ref and declared dependent step_output bindings are preserved. Models no
longer copy selected-device/source identity properties or sourceMessageId for new
goals: the server supplies exact identities and current-message provenance.
Retained goals keep their original historical sources.

This is an explicit wire projection in all three configured output modes, not a
new API/stored plan version or hidden profile switch. The policy fingerprint
pins it. Normalization/validation is inside the same A2 usage/reservation boundary
before unchanged canonical, registered-parameter and graph checks. Failed lowering
retains actual usage and can use the existing single format correction. A saved
successful ledger reply is already canonical and is never lowered again on replay.

Planner questions use missingFields=intent|target for routing ambiguity. Domain
questions belong to the selected skill; a planner question such as tramName is
rejected instead of shown to the user. Selected dashboard reviews expose their
actual accepted name, resourceType=DASHBOARD, reviewStatus and requiredIntent.
The guide explains short edits against that subject, distinguishing telemetry
devices from the dashboard being edited, and supplies complete create/refine
wire examples. CONTINUE on a WAITING_CONFIRMATION dashboard is rejected before
STEP with PLANNER_ACCEPTED_DASHBOARD_REQUIRES_AMEND. This is a structural guard,
not automatic semantic rerouting or permission to execute.

Offline coverage checks all output modes, exact scalar/array identities, forged
sources and unknown/mismatched references, question ownership, empty goalIds,
complete examples, preserved fact/output bindings, accepted-review context and
A2 failed-usage/canonical-replay behavior. Real-model improvement after this fix
has not been measured. Campaign05 remains4/11 first-pass,3/7 recovered,7/11 final,
4/6 dialogues; the original two families remain1/5 and0/2. No provider settings,
defaults, historical criteria or recorded scores are changed.


Planner-fix validation:201 AI-service offline tests passed with three Docker-backed
classes excluded. Strict OpenSpec86/86, structure and whitespace checks passed.
Logs: artifacts/a5-planner-fix-offline-suite.log, a5-planner-fix-openspec.log and
a5-planner-fix-structure.log. No paid replay or service/provider configuration
change was performed for this fix; real-model routing/first-pass improvement and
remaining STEP format reliability are unproved.


## A5 planner campaign06 and offline context follow-up

Two authorized STANDARD cohorts used the same frozen original two dialogues and
shared40-call/20-minute/USD4 reserved caps (corrections ON, model judge OFF).
Before (planner references v1/routing v4): first-pass2/5, recovered1/3 initial
failures, final3/5,1/2 full dialogues. After schema-derived operation descriptions
and routing v5: first-pass2/5, recovered0/3, final2/5,0/2 dialogues. Both quality
gates FAILED. No prior score or criterion was changed; these known-case linked
samples are not independent A6 qualification.

The first run had no UUID-format errors and corrected one wrong CONTINUE through
A2. Rename was falsely UNSUPPORTED; the later edit retained the wrong name. The
catalogue fix did not demonstrate improvement: rename asked unnecessary intent/
target/name clarification, a current doors card was omitted, and another planner
bound a review as a device twice. A separate no-rescore audit found all six
requested roles before and a missing current doors card after, despite that
permissive initial turn's formal PASS. READY alone is not completeness evidence.

A2 ledger:19 calls (17 primary,2 format corrections),140,846 input (22,400 cached),
64,481 output (58,624 reasoning included),508,514ms model latency;826.5s live
campaign wall time including the intervening fix/build/startup. All ledger usage
was present. USD1.90 is a charged/reserved bound, not billing; actual cost remains
UNKNOWN_UNPRICED. All four owned fixture cleanups passed; no dashboard mutation,
provider setting/default change or judge calls occurred. Both source versions
were fixed within their live cohort; historical live-12 and campaign05 remain.

Further source-only planner-references-v2/routing-v6 makes input reference schemas
field-specific, excludes dashboard reviews from eligible device/source bindings
and advertises bindingFields. activeSubject matches an exact stored review focus;
selected devices get CONTEXT_RESOURCE roles and neutral UI_CONTEXT selection
metadata. No match means no fabricated subject. This is factual context and schema
lowering, not semantic routing or another question blacklist. Intent selection and
raw request/history remain model-owned. The v2 follow-up has205 passed AI-service
offline tests (three Docker classes excluded), including all output modes and the
new reference/subject checks. It has NOT been live-replayed or redeployed. The
running service remains the measured routing-v5 cohort version. No third paid
cohort was run beyond the predeclared two-cohort campaign.

Evidence: artifacts/a5-planner-verification-20261028-06/report.md, analysis.json,
before/summary.json, after/summary.json and usage-ledger-evidence.json. Remaining
A5/V/R/A3/UI/A6 acceptance stays open. Next: a fresh predeclared capped replay of
the explicit subject/per-field reference contract, then independent completeness
and preservation work in the dashboard skill.


## A5 context-first clarification: campaign07

The common trust policy now resolves exact message, mandatory dialogue, stored
focus and confirmed facts before asking. A short follow-up need not repeat its
established subject or supplied values. A question is warranted only if remaining
ambiguity changes the safe next action. planner-routing-v7 applies this to ASK
and keeps explicit new workflows possible. The common policy digest now enters
the pinned runtime fingerprint. This is prompt/context guidance, not semantic
server classification or another keyword/regex rule.

Previously offline-only planner-references-v2 (field-specific bindings, activeSubject,
context-resource roles) and the new clarification policy were deployed and tested
with the unchanged two frozen original dialogues in STANDARD: corrections ON,
model judge OFF.206 AI-service offline checks passed (three Docker classes
excluded), including preserved explicit new-workflow selection across all modes.

Actual campaign07 FAILED: first-pass2/5, recovered1/3 initially unsuccessful steps,
final3/5 and1/2 full dialogues. Rename still asked device-versus-dashboard and
requested the supplied name. Later edit failed exact name and door-history role/
view checks. Separate three-card create/move passed after one STEP format
correction. No planner UUID/resource-kind error appeared in this sample, but no
semantic-quality improvement is established. Separate no-rescore audit found
history:doors omitted from the first request despite its permissive formal PASS.
Prior criteria/scores and historical live-12 remain unchanged.

A2 durable ledger:10 calls (9 primary,1 format correction),97,488 input tokens
(21,632 cached),35,799 output (32,832 reasoning included),186,978ms model latency.
All usage is present; no judge/domain/outcome recovery calls. USD1.00 is the
charged/reserved bound, actual billing UNKNOWN_UNPRICED. No dashboard mutation;
both owned fixtures cleaned. Source stayed fixed through generation; provider/
model/output settings and STANDARD were unchanged at postflight.40-call/20-minute/
USD4 reserved caps were retained; one actual generation cohort was run.

An initial service-restart preflight503 occurred before model/fixture initialization.
Its BLOCKED report was preserved, and a zero-usage link retained the same wall
clock/caps for the actual live cohort. Shared wall348.1s, generation wall198.3s.
No failed generation or trial was retried or removed. Evidence:
artifacts/a5-active-subject-verification-20261029-07/report.md, analysis.json,
live/summary.json and usage-ledger-evidence.json. Remaining A5/V/R/A3/UI/A6 gates
stay open. Next: examine/compare planner conversation representation and routing
using the exact failure, plus separate STEP completeness. Installing an agent
framework or adding more instructions is not itself evidence of quality.
