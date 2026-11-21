# Spring AI assistant

For a detailed Russian source walkthrough of the UI-to-action chain and project
architecture, see [AI Agent request walkthrough](ai-agent-request-walkthrough.ru.md).

The Java backend uses Spring Boot 4.0.9, Spring Cloud 2025.1.4 and Spring AI
2.0.2. Java source/bytecode remains Java 21. Boot manages the compatible
Framework, Kafka and Micrometer versions. Springdoc uses its Boot 4 line.

## Runtime ownership

### LangGraph4j core integration — 2026-11-11

`ai-agent-service` pins `org.bsc.langgraph4j:langgraph4j-core:1.9.9`.
`AgentGraphRunner` is the application port; `LangGraphAgentRunner` is the only
library adapter. Its actual `StateGraph` nodes and conditional edges replace
the former recursive planner/step/replan/domain-repair traversal. The same
runner handles bounded in-turn context continuations and explicit confirmation:
validate review -> persist dispatch intent -> owner execution -> receipt/readback.
`IntentPlanGraph` still validates acyclic user-plan dependencies; library
control-flow cycles do not relax those constraints.

Only core is imported. There is no LangChain4j/stock agent executor or replacement
Spring AI client. Model nodes still enter the A2 reservation/usage path and the
existing single-exchange Spring AI transport. Context graphs have no independent
durable resume authority; each external attempt remains in the existing ledger.
The serial graph uses a caller-thread emitter executor, preserving request-local
trace context and avoiding an unbounded background dispatcher. Async specialist
scheduling and parallel subgraph checkpoint namespaces are not implemented.

The custom native `BaseCheckpointSaver` commits a primitive-only `GraphExecution`
inside `ai_orchestrations.state_json` using the existing `IntentPlanStore.commit`.
Plan status, facts/review references and checkpoint move in the same short
transaction and revision/lease fence. No generic PostgreSQL saver or second
checkpoint database is added. Graph state contains only a bounded `route` string;
clients, credentials, raw prompts and provider objects never enter it.

New graph plans use persisted `schema_version=2`; legacy records stay version1
and are not backfilled. The current reader accepts both and rejects mismatched
schema/checkpoint envelopes. Engine `langgraph4j-1.9.9` and graph definition
`voltcanary-agent-graph-v1` are pinned in each new plan and cannot change in an
ordinary commit. The assistant profile retains `engine=spring-ai-2` and adds
`graphEngine` / `graphDefinition` diagnostics.

A question or confirmation ends the current graph turn with its waiting state
persisted. A fresh authenticated user request re-enters from canonical plan
state; it does not blindly run an old checkpoint's next node. Repeating an
already claimed request replays its stored state without model I/O, including
an abandoned PENDING/UNKNOWN turn. A crash after send cannot authorize inference
replay; the existing ledger keeps its reserved/unknown outcome. Owner uncertainty
continues to use original receipts and GET/reconciliation rules.

Continuing generation on a pre-graph active plan returns409
`GRAPH_ENGINE_MIGRATION_REQUIRED`; an incompatible engine/definition returns409
`GRAPH_ENGINE_VERSION_CHANGED`. There is no silent generator fallback or implicit
migration. Existing history, cancellation and original saved-review confirmation
remain available.

New conversations and plans retain their history and graph checkpoints across
service restarts. Pre-release test plans require no in-place migration.

The existing `AI_ASSISTANT_V2_ENABLED` default-off generation gate is retained;
no provider settings, model campaign or deployment defaults change here.
The ordinary-API dashboard lifecycle passes13 deterministic Docker checks on
2026-11-12, including two restarts and exact same-ID update readback; see the
[verification runbook](../flows/ai-creation-verification.md#langgraph-ordinary-api-docker-lifecycle--2026-11-12).
Requirements/specialist semantics, browser acceptance and
A5/A6 model-quality gates remain separate unfinished work.

### Internal specialist integration checkpoint — 2026-11-12

The collaboration package implements typed requirement proposals, explicit user
values/choices, dual question/requirements revision checks, dependency invalidation
and one root question arbiter. Duplicate questions merge; competing values produce
CONFLICT and require user clarification instead of majority voting. The canonical
collaboration schema is included as isolated `collab.*` definitions; existing
planner/step projections do not acquire the specialist envelopes.

`JdbcCollaborationStore` and migration V011 add one plan-owned JSON snapshot and
append-only revision snapshots/digests. This stores requirements and task records,
not another graph checkpoint or dispatch ledger. Writes use the existing
conversation -> plan lock order, root request/revision/lease fences, database time,
CAS and idempotent request keys. The task deadline is bounded by plan origin and
the original A2 plan deadline; reading or restarting cannot extend it.

The internal requirements transition uses the same transaction for the
collaboration journal, root plan revision, question or reply, and revocation
of affected READY reviews. The answer adapter checks the question and
requirements revisions and binds CHOICE, TEXT and VALUES to the claimed input
digest and journaled message ID. The authenticated
`GET /ai-agent/plans/{planId}/requirements` reads the scoped snapshot.
`POST /ai-agent/plans/{planId}/requirements/answers` uses the assistant
enablement gate and atomically claims, journals and commits an answer for the
registered text slot profile. Consultation inspection at
`GET /ai-agent/plans/{planId}/consultations/{taskId}` returns task status,
revision, exchange count and children. The corresponding `POST .../cancel`
accepts `expectedTaskRevision` and `requestKey`, fences the selected task
and its descendants, and replays the journaled result for the same request.
The graph publishes single-field name questions without choices for device and
dashboard creation in the same terminal commit. A typed answer updates the
step's name input and marks the step READY for the next turn. The next step
can be selected explicitly by passing `continueStepId` to the conversation
message endpoint. The assistant's plan view uses this path to continue
preparation. The server checks the current conversation revision, step
readiness and successful dependencies before entering the step directly.
The next step
can dispatch the registered name assessment in a LangGraph4j subgraph. It
reads only the step's confirmed name slot and uses the same pinned provider,
root budget and attempt ledger. Task summaries are discoverable through
`GET /ai-agent/plans/{planId}/consultations`.
When a dashboard step requests an unresolved name, the same role can ask for
that slot. The coordinator commits the specialist question with the root
pause. A typed answer followed by explicit step continuation reuses the
existing consultation task and reserves its second exchange.
On its first exchange with an already confirmed name, the assessment role
can request the registered workflow context or delegate readability to
the depth-three name role. Workflow context supplies only the selected
device labels and snapshot ID. A parent continues after its declared child
tasks finish and receives only child payloads whose read dependencies are
still current.
The assistant drawer reads the task summaries when it opens a saved plan or
receives a new plan revision. This read does not initiate an exchange.
For a saved single text name question, the chat composer sends the typed
answer with both revisions and reloads the conversation. The operator can
then continue the ready step from the plan view.

`ConsultationPolicy` bounds logical depth at3, children at3 per parent, lifetime
nodes at12, simultaneous RUNNING tasks at2 and exchanges at2 per obligation.
It validates declared loaders, delegation, evidence, original attempt/continuation/
cancellation epochs and exact read dependencies. An unrelated requirements edit
can rebase a result; changed/revoked dependencies make it STALE. Waiting states
release compute capacity while preserving lifetime counters and absolute deadlines.

`AgentGraphRunner.runTurn` supports compiled native subgraphs without independent
savers. `ConsultationWorker` runs claim -> fresh authority/read-set guard ->
exchange -> acceptance inside such a subgraph. Its CONSULT phase uses the same
`RoutedModelCalls`, A2 reservation/uncertainty accounting and single-exchange
Spring AI transport. Prompt projection includes only declared slots/artifact IDs
and registered capability metadata. No nested model callback, second reservation
system, provider client or automatic retry is added.

These are internal, fixture-tested components. They are not yet registered in
the primary assistant's production skills/loaders or exposed through requirements/
consultation APIs and UI. A bounded durable executor, continuation admission and
applicable end-to-end owner/UI acceptance remain pending under task2.4/3.2.
Default policies and their existing version digests remain unchanged; merely
accepting the additive CONSULT phase does not enable consultations. This checkpoint
does not close task2.4 or qualify real-model output, cost or autonomous operation.

`PrimaryAssistantConfiguration` composes one durable assistant runtime.
New `/ai-agent/plans`, clarification and conversation-message requests enter
`PrimaryAssistant`; they do not switch to the former generator when disabled or
after an error. The existing `AI_ASSISTANT_V2_ENABLED` switch gates generation;
false returns 503 `ASSISTANT_GENERATION_DISABLED`. Read, cancellation and saved
review execution remain independent of model availability. Existing legacy
reviews can be continued by importing their authorized base into a new plan.
The direct legacy clarification route idempotently imports its original request
and exact assistant question into persisted conversation history before the new
answer enters Spring AI. This import makes no model call or domain mutation.

The state machine retains durable steps, dependency bindings, exact pending
questions and receipt reconciliation. Spring AI owns model exchanges through
`ChatClient`, provider models, typed messages and `ToolCallback` contracts.
`SpringAiConversationContext` projects authorized bounded persisted dialogue
into user/assistant messages and retains identities and the exact question in
the current request. There is no independent in-memory conversation history.

`SpringAiModels` constructs a short-lived provider client from the account's
resolved model and encrypted credentials and closes its connections after the
exchange. OpenAI and compatible providers use Chat Completions; Anthropic uses
Messages; Gemini uses GenerateContent. The former OpenAI Responses HTTP
transport is retired. Provider/model selection remains PINNED for the plan. The routing policy digest
retains its existing algorithm and limits so an in-progress plan does not become
unusable at cutover; the framework engine version is reported separately.
Previously completed request IDs replay their persisted outcomes before model I/O.

`SpringAiExchange` applies explicit output limits, structured output or one
selected native proposal tool contract. Automatic Spring AI tool-loop execution is
disabled. A tool response is a proposal for canonical validation and domain
compilation; it never grants access or executes a mutation. Domain commands
still require persisted review version/digest, explicit confirmation, access
revalidation and durable idempotency receipts. Context continuations and format
corrections re-enter the durable state machine and shared budget ledger.

### Dashboard native tool calling

Dashboard prompts are separate, generated by `DashboardSkillGuide` from the
registered typed intent. They share design/capability rules, with different
purposes, preservation constraints, base requirements and operation schemas:

| Intent | Native Spring AI tool | Base |
| --- | --- | --- |
| `CREATE_DASHBOARD` | `prepare_dashboard_creation` | New dashboard; backward local `@operationId` widget references |
| `REFINE_DASHBOARD_REVIEW` | `prepare_dashboard_refinement` | Selected unexecuted immutable review; exact existing widget keys |
| `UPDATE_DASHBOARD_DRAFT` | `prepare_dashboard_update` | Existing dashboard ID and freshly authorized version/revision |

Configure the selected account model with `supportsTools=true` and
`responseFormat=tool` to use `NATIVE_TOOLS`. Capability is explicit: the service
does not infer it from the model name, probe it or fall back after rejection.
JSON_TEXT and STRICT_JSON remain explicit alternative configurations. Adding
these tools does not automatically rewrite stored account model settings.

Only the selected tool is exposed for a dashboard writer. Its strict argument
schema derives from that skill's registered step-response and current authorized
operation schema. It uses the existing `{proposal: ...}` wire envelope and
normalizes to the unchanged canonical response. PREPARED contains one batch of
semantic operations, including `place_widgets`, `resize_widgets`, `set_data`
and `set_presentation` where allowed. QUESTION and NEED_CONTEXT return through
the same durable state machine; a tool is not permission to execute a loader or
mutation directly. No provider call is added per operation/widget.

The SDK returns one tool call without invoking its callback or continuing a
tool loop. `SpringAiExchange` checks the exact selected name and rejects multiple
calls or plain-text substitutes, retaining observed usage. Direct invocation of
the proposal callback is blocked. Planners, independent assessors and other
skills retain `assistant_proposal`. All prepared changes still pass canonical
schema, widget-reference and domain validation, immutable review and explicit
confirmation before the existing executor saves them. Draft conflict handling,
receipt reconciliation and call/cost reservations remain unchanged.

Local HTTP fixtures verify the actual Spring AI request and absence of an SDK
continuation. They do not establish how reliably a real model selects widget
targets or satisfies user intent; the frozen quality gates remain open.

### Contract hardening after the 2026-11-02 native diagnosis

`strict-projection-v2` compiles the planner's finite decision conditionals into
disjoint nested `anyOf` objects. PLAN/AMEND require steps and null question/target;
CONTINUE requires a target with empty step/proposal arrays; ASK requires an
actual question. Array bounds survive lowering. The outer provider schema stays
an object; unsupported `allOf/if/then` are not sent. This follows the supported
[OpenAI structured-output subset](https://developers.openai.com/api/docs/guides/structured-outputs);
configured providers must qualify the subset and never downgrade silently.

`planner-references-v4` excludes PLAN when typed planSummary already has steps
and excludes AMEND/CONTINUE for an explicitly empty plan. An empty target ASK
cannot replace dispatch of a known activeSubject: a genuine competing target
requires scoped choices, while domain values/questions belong to the skill.
Exact messages, goals, question identities and canonical graph checks remain.

`WidgetDataCompatibility` shares the owner type/visualization relation between
schema preparation and final data resolution. The dynamic set_data schema pairs
widget keys with type-compatible authorized dataRefs; grouped equivalent choices
avoid repeating a separate schema for every widget. A rejection reports the
operation path, widget type/view and at most twelve offered references. A source
field and a registered metric with the same label are not interchangeable.
View compatibility follows the batch in order, including a preceding show_history
view change; the wire does not freeze an editable visualization to its old value.
Final compilation still validates every preceding operation and actual binding.
TOP/BOTTOM require a null anchor; BEFORE/AFTER require an anchor. Target arrays
are nonempty except the existing global set_period convention.

For CREATE_DASHBOARD model requests, PREPARED.command.payload.dashboardPeriod is
mandatory on JSON_TEXT, STRICT_JSON and NATIVE_TOOLS wires. Its enum derives from
the existing set_period contract. The compiler default remains LAST_24_HOURS
when no global period is requested. Chart overrides remain separate. Prompt
examples use the same model wire; canonical examples/reviews keep their existing
operation protocol. The transport removes dashboardPeriod and inserts one
global set_period before widget creation, using a noncolliding technical ID.
Identical redundant global operations are coalesced; conflicting choices are
rejected. The writer has room for 23 operations plus the lowered global one.
Format correction raises a rejected canonical global operation back into the
same dashboardPeriod wire field, preserving its explicit choice and chart overrides.
QUESTION/NEED_CONTEXT are unaffected. Stored reviews never acquire a new field.

`PrimaryDashboardVerification` checks prepareIntent.period against the resulting
definition.defaultContext.time.preset, as it already does for the name. It checks
compiler effects, not whether the model understood a natural-language period.
`dashboard-skills-v6` and `dashboard-design-v2` distinguish title/details/map
controls/map footer visibility from actual freshness; they do not invent data.
Planner routing instructions are shorter and domain interpretation stays in skills.

Failed MODEL_CALL traces now include actual usage/cost status when available.
Rejected proposal diagnostics retain only whitelisted decision/outcome values,
presence flags and bounded step/operation counts. No raw argument/body/text is
added to traces. Reservations, confirmation, access, revisions and receipts stay
unchanged. Offline passing tests do not close the failed live quality gates or
the still-unverified saved-create/update lifecycle.

SDK and Spring retry counts are zero (Google SDK: one attempt). Every primary
exchange has a durable reservation and trace start. Truncated/refused responses
retain available usage and cannot become executable plans. Unknown usage stays
unknown. Logs and traces exclude keys, raw provider payloads and private
reasoning. The canonical serialized request includes the full instructions,
schema/tools and bounded history; routing reserves a conservative byte-based
token estimate plus SDK framing allowance before the lossless role projection.

## Platform JSON compatibility

H2 is explicitly upgraded to 2.5.252 because Boot 4.0.9 manages 2.4.240, whose
[cross-connection CHECK regression](https://github.com/h2database/h2database/issues/4302)
breaks migrated analytics metadata tests. Production PostgreSQL constraints are
retained.

Application contracts and NetworkNT schemas retain Jackson 2 during the Boot 4
migration using `spring-boot-jackson2`. HTTP converters prefer `jackson2`.
Programmatic domain clients use `JacksonRestClients.builder()` so JSON nodes
and DTOs retain their existing semantics. Spring AI/framework internals may use
Jackson 3. Kafka event wire formats and persisted data do not change.

## Verification

Run `mvn test` in `backend`, then `npm run structure:check` and
`npm run openspec:validate` at the repository root. `SpringAiExchangeTest` uses
local HTTP fixtures for credential/model isolation, native provider endpoints,
schema/tool contracts, accounting, truncation and no-retry failures. Existing
durable plan/context/review tests retain confirmation and recovery coverage.
Offline passing results do not qualify model quality; historical A5/A6 failed
or incomplete quality acceptance remains open. No paid trial is part of these
checks.

Migration owner: [rebuild-assistant-with-spring-ai](../../../openspec/changes/rebuild-assistant-with-spring-ai/proposal.md).

## Offline reliability corrections

The A5 follow-up keeps the frozen evaluation corpus and graders unchanged. The
wire context now identifies the exact current change, retained goals, pending
question and accepted review/name/inventory explicitly. An independently seeded
conversation with one explicitly selected authorized base review exposes that review as its work
subject. The outcome assessor never receives this writer-goal projection.

Dashboard operation IDs are technical labels. Duplicate IDs without any local
references to that ID can be replaced deterministically before canonical ledger
storage and domain validation. Targets and operations are unchanged. Referenced
collisions remain ambiguous and are rejected; unknown fields, resource keys and
forward references are never repaired by guessing. Format correction includes
the selected outcome's required/allowed fields and unexpected supplied fields.

AI generation clients renew a near-expiry session before submitting a message.
The current turn budget is four minutes; a new primary submission requires more
than five minutes of remaining access-token lifetime. Otherwise it returns
`401 ASSISTANT_SESSION_REFRESH_REQUIRED` before opening a model attempt. The
frontend and evaluation client share their normal single-flight refresh path;
refresh failure stops submission. Stored-result replay and read-only history do
not require a new planning lease. No refresh credential is sent to the AI service,
and downstream authorization remains enforced.

An unsuccessful attempt stays unsuccessful. `acceptedReview` remains a distinct
read-only projection of the last accepted immutable review. Recovery evaluation
reads that projection rather than treating the failed attempt as a new READY
review. Independent evaluation branches use separate conversations and explicit
immutable parent reviews; concurrent writes in one conversation still return 409.
The foreign actor is initialized through the ordinary account API before testing
cross-account denial. Driver version and source hashes identify these harness
changes separately from model quality; historical reports are not rescored.

Local trace/history smoke uses an isolated deterministic Chat Completions fixture
configured before the first message. The fixture emits assistant-v2 envelopes;
logical attempts and observed provider completions are counted separately. No
real-model campaign or quality qualification is implied by these checks.

Provider usage events carry the corresponding A2 model-attempt identity. Equal
token counts from distinct attempts persist as separate events; replay of the
same event remains idempotent. This prevents trace deduplication from undercounting
provider completions without changing budget accounting or introducing retries.

## Widget catalogue and embeddings

Dashboard-owned `WidgetCapabilities` and `WidgetManifests` remain authoritative.
The widget-types endpoint supplies purpose, time basis, units, limits, binding and
visualization compatibility. `settingsSchema` is generated from the same setting
records used by validation, including nullable defaults, actual boolean values
for AUTO_BOOLEAN, enum values, text bounds and field-set constraints. The AI
projection retains full `settingContracts` and `settingsSchema` alongside the
existing compact `settings` hint; it no longer drops labels and constraints.

Fetched widget descriptors have a separate bounded secret scan: at most 64 types,
12 levels, 16384 items and 220000 text characters, with the same forbidden
credential fields/values and finite-number checks. Only the server-fetched widget
catalogue uses this path. All user input and the remaining workflow context retain
the original 8-level, 2048-item, 32768-character guard. The full skill-request size
limit still applies. This prevents full owner schemas from consuming the much
smaller client-input budget without weakening client validation.

`WidgetCatalogueSearch` adds advisory `widgetCapabilityMatches` to primary
dashboard writer context only. It indexes widget purposes and individual setting
descriptions, ranks lexical matches and optionally cosine similarity, and returns
at most eight exact widgetType/settingKey references. The complete catalogue is
always retained: a search miss is not an unsupported capability. Retrieved hints
do not enter planner or independent assessor inputs, grant resource access or
change domain validation, review, confirmation or execution.

The immutable in-process index keeps at most two catalogue snapshots. Its digest
covers full catalogue content, retrieval version and endpoint/model identity.
Embedding model IDs must be pinned; replacing weights behind the same ID requires
restart or a new versioned ID. There is no shared query-vector cache, tenant
telemetry index or test-answer index. Bounds: 64 widget types, 512 documents,
2048 characters per indexed document, 4096 per redacted query, 8192 vector
dimensions. Invalid/zero/nonfinite/mismatched vectors reject semantic retrieval.
Embedding failure preserves lexical results and all owner contracts, with an
explicit diagnostic. The per-turn wire adapter reuses hints for format correction
instead of repeating embedding work.

`SpringAiCapabilityEmbeddings` uses the Spring AI 2 OpenAI-compatible embedding
SDK with dedicated deployment configuration and no account chat credentials.
Embeddings default off. Enabling them makes actual embedding requests: one batch
on an index miss plus one query request, each with 100-5000 ms timeout and zero
retries. They are retrieval infrastructure calls, reported separately through
`embeddingCalls` and the existing CONTEXT trace; chat generation keeps its A2
ledger and limits. No chat fallback, model escalation or training is introduced.

Configuration (Compose forwards these variables; Kubernetes/Helm deployments can
set the same variables on the AI service):

| Variable | Default / meaning |
| --- | --- |
| `AI_WIDGET_EMBEDDINGS_ENABLED` | `false`; explicit activation |
| `AI_WIDGET_EMBEDDINGS_BASE_URL` | App: `http://localhost:11434/v1`; Compose: `http://host.docker.internal:11434/v1` |
| `AI_WIDGET_EMBEDDINGS_MODEL` | Empty; required when enabled, use a pinned multilingual embedding model served by that endpoint |
| `AI_WIDGET_EMBEDDINGS_API_KEY` | Empty for local service; dedicated secret if required |
| `AI_WIDGET_EMBEDDINGS_TIMEOUT_MS` | `3000`, allowed 100-5000 |

Use only a local or approved embedding endpoint: catalogue text and the redacted
current message are sent there. Existing conversation redaction does not make
arbitrary user text anonymous. No model download, real embedding generation or
real-model test was performed for this change. Unit tests use controlled vectors
and an in-process HTTP embedding fixture; they qualify wiring and boundaries,
not multilingual semantic quality. The frozen evaluation corpus and grader remain
unchanged and the existing model-quality gates stay open.

## Lossless wire context compression (2026-10-30)

Canonical owner context remains complete. Dashboard writers use
`widget-catalogue-wire-v2`: exact identical setting contract/schema/hint triples
are stored once in `dashboardWidgetParameters`. Each widget's
`capabilities.settingRefs[settingKey]` addresses its entry; `settingsEnvelope`
retains object-level schema metadata. Widget overview, compatibility, bindings,
units and limits remain in the complete `dashboardWidgetTypes` index. Distinct
defaults or constraints are never merged. Legacy descriptors without a complete
schema remain unchanged. Compaction applies only when it saves space.

`widgetCapabilityMatches.matches[].parameterRef` points into the same exact
dictionary. A retrieval miss never removes a supported parameter. Identical
catalogue/dictionary bundles across workflow loads are emitted once; subsequent
loads have JSON Pointer `dashboardWidgetTypesRef` and
`dashboardWidgetParametersRef` paths. Different owner bundles and per-load data
choices remain distinct. No embedding is needed for these savings.

`primary-prompt-v4` replaces duplicated change-context arrays with explicit
`retainedGoalsRef`, `pendingQuestionRef`, `resolvedInputsRef` and
`widgetInventoryRef` paths to their original values in the same request. Exact
source text, dialogue, accepted definition, goals and bindings remain available.
The writer projection never mutates canonical requests or assessor inputs.

Routing still uses the same conservative UTF-8 byte estimate plus framing
allowance, with unchanged input/output/window/cost/call limits. An offline
reconstruction using saved failing Docker-turn context, dialogue and accepted
review shrank from 108075 to 87011 bytes; the same admission limit now accepts
it. Two repeated workflow loads shrank from 171867 to 106075 bytes. This is not
a byte-identical replay of the original wire or a real-model semantic result.
Round-trip tests reconstruct every original setting schema/contract and preserve
different defaults, choices and per-device references.
