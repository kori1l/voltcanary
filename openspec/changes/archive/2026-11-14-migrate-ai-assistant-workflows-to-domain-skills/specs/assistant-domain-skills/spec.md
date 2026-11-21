## ADDED Requirements

### Requirement: Configurable platform skill integration

New configurable workflow skills SHALL consume the normative VCL registry, dataset/connection capabilities, strict import manifests and shared owner reviews/receipts, while preserving already implemented legacy/native dashboard contracts and historical quality gates.

#### Scenario: New data operation
- **WHEN** the assistant proposes a table, mapping or temporal rule
- **THEN** the same typed compiler and owner API used by the manual workbench validate it without a generic SQL tool

#### Scenario: Adapter capability absent
- **WHEN** a customer source cannot prove safe write or complete temporal capture
- **THEN** the skill reports the concrete limitation without a hidden fallback



### Requirement: Native contract hardening
The provider wire SHALL encode planner decisions as disjoint supported variants,
including conditional null/nonempty fields and typed initial/existing-plan
decision limits. Canonical schema, graph and domain validation SHALL remain
independent, with no hidden model retries or user-language routing rules.

#### Scenario: Contradicting planner fields
- **WHEN** a planner proposes PLAN with a non-null question or no steps, CONTINUE without a target, or initial PLAN for an existing plan
- **THEN** the applicable wire schema rejects the combination before it can become an executable plan

#### Scenario: Selected dashboard becomes a domain question
- **WHEN** a planner asks for target without scoped choices while activeSubject already identifies the dashboard
- **THEN** the proposal is rejected with a skill-dispatch diagnostic; unresolved domain values belong to the selected skill and the exact user request remains in durable dialogue

#### Scenario: Data replacement offers another widget family
- **WHEN** set_data references a field with no owner-supported view for the selected widget type and visualization
- **THEN** the target-paired type schema and ordered view-compatibility check reject it with the operation path, target type and bounded compatible dataRef list, without silently replacing its role or device; a valid preceding view change remains possible

#### Scenario: Creation chooses global and chart periods
- **WHEN** a new creation writer prepares a dashboard
- **THEN** its model wire explicitly chooses dashboardPeriod independently of any chart period; the server lowers that choice to canonical global set_period before widget operations and verifies the saved definition projection's default period

#### Scenario: Stored review predates the new wire
- **WHEN** an authorized saved review is read or executed
- **THEN** its canonical operation/definition contract remains unchanged and does not require the new model-only dashboardPeriod field

#### Scenario: Provider completes an invalid proposal
- **WHEN** canonical validation rejects a completed response
- **THEN** ledger and failed-call trace retain observed usage and bounded technical proposal shape, excluding raw provider bodies, arbitrary values and private reasoning

### Requirement: Reviewed native dashboard tools
For a configured native-tool model the runtime SHALL expose the tool for the
selected dashboard creation, review-refinement or draft-update skill using its
registered schema and focused guide. Tool arguments SHALL enter existing durable
validation and immutable review without automatic SDK execution or hidden calls.

#### Scenario: Model proposes movement through a dashboard tool
- **WHEN** the model calls the selected refinement or update tool with place_widgets using authorized inventory keys
- **THEN** the server validates the same canonical semantic operations, prepares a review and requires confirmation before saving

#### Scenario: Model returns the wrong tool or multiple calls
- **WHEN** a provider returns another skill's tool name or more than one call
- **THEN** that exchange is rejected with usage retained, without execution, an SDK continuation, model replacement or output-mode fallback

#### Scenario: Creation and editing remain distinct
- **WHEN** the selected typed intent changes between creation, unexecuted refinement and existing draft update
- **THEN** the tool name, focused prompt and operation schema correspond to that intent while planners and independent assessors retain the generic proposal contract

#### Scenario: Explicit model capability
- **WHEN** responseFormat=tool and supportsTools=true are configured
- **THEN** the primary runtime uses native tools; incompatible configured capability combinations fail before a provider call rather than probing the model

### Requirement: Lossless bounded context compression
The runtime SHALL compress repeated owner widget parameter definitions and edit
context through explicit wire references while retaining every discoverable
capability, exact schema constraint, source message and accepted definition.
Compression SHALL NOT weaken canonical validation or increase input, routing,
output, cost or call ceilings.

#### Scenario: Search misses an available parameter
- **WHEN** hybrid or lexical retrieval does not rank a supported widget setting
- **THEN** its exact contract remains addressable through the complete widget index and shared parameter dictionary, without another model call

#### Scenario: Shared parameter differs between widgets
- **WHEN** two widget types use the same setting key with different defaults or constraints
- **THEN** their distinct contracts remain distinct in the wire dictionary and decode to the original owner catalogue

#### Scenario: Repeated edit metadata
- **WHEN** the writer projection references retained goals, question, resolved inputs or base inventory
- **THEN** the exact canonical values remain present once at the referenced paths and independent assessment does not inherit writer-only goals

#### Scenario: Repeated workflow loads
- **WHEN** multiple authorized workflow loads contain identical catalogue and parameter dictionary bundles
- **THEN** later loads may reference the first bundle by exact JSON Pointer while preserving their distinct data choices; different bundles are not merged

### Requirement: Grounded widget capability retrieval
The assistant SHALL preserve dashboard-owned parameter descriptions, defaults,
constraints and compatibility, and MAY rank those contracts through lexical and
embedding similarity without excluding authoritative catalogue entries or
changing permission, validation, confirmation and execution boundaries.

#### Scenario: Search identifies a parameter
- **WHEN** a dashboard writer searches available widget capabilities
- **THEN** results identify exact widget types and setting keys with their source catalogue digest, while complete current contracts remain available

#### Scenario: Catalogue or embedding model changes
- **WHEN** a schema, description or embedding model identity changes
- **THEN** retrieval uses a newly identified bounded index and never mixes incompatible vector spaces

#### Scenario: Full owner catalogue exceeds client input size
- **WHEN** the fetched owner catalogue contains complete parameter schemas exceeding the ordinary client-input item budget
- **THEN** only that fetched catalogue uses a separately bounded secret scan, while client input, other workflow data and full model-request limits remain enforced

#### Scenario: Embeddings are disabled or unavailable
- **WHEN** embeddings are disabled, invalid or fail
- **THEN** retrieval reports lexical-only operation and retains the full catalogue, without hidden retries or another chat-model call

#### Scenario: Offline retrieval verification
- **WHEN** the user has prohibited real-model tests
- **THEN** deterministic embedding doubles and local HTTP fixtures verify integration without generating real embeddings, modifying the frozen corpus or claiming semantic acceptance

### Requirement: Bounded offline reliability corrections
The assistant SHALL preserve source text, accepted review identity and retained
goals in an explicit edit context without semantic phrase matching. Technical
normalization SHALL not infer user intent or silently repair ambiguous references.

#### Scenario: Unreferenced operation labels collide
- **WHEN** valid operations repeat a technical ID that no local target references
- **THEN** the server may assign distinct deterministic labels before canonical storage while preserving all operations and targets; a referenced collision is still rejected

#### Scenario: Access token cannot cover a new planning turn
- **WHEN** remaining validated token lifetime is at most five minutes for the current four-minute planning budget
- **THEN** a new submission returns an authentication renewal response before any provider attempt; clients use normal session refresh before submission without repeating completed model work

#### Scenario: Failed edit retains an accepted preview
- **WHEN** preparation fails while a prior review remains authorized
- **THEN** the failed attempt remains visible as failed and the accepted review is independently readable as a read-only preview; no confirmation or execution is inferred

#### Scenario: Evaluation actors and independent branches
- **WHEN** the offline driver evaluates foreign access or independent edits
- **THEN** the second actor has an initialized account and each independent branch has its own revision stream with an explicit authorized base; same-conversation concurrency protection remains intact

### Requirement: Versioned focused skill and output contracts
Every domain skill SHALL declare purpose, input/context rules, permitted operations, preservation constraints, clarification/unsupported conditions, response schema, diverse examples and acceptance checks. The runtime SHALL select guidance by typed skill/action, preserve mandatory dialogue and use native constrained output when supported by the configured profile, with canonical server validation in every mode.

#### Scenario: Current review is refined
- **WHEN** a short edit follows a previously accepted dashboard review
- **THEN** the request includes exact current text, required history/question, confirmed facts and the accepted widget inventory rather than stale initial literal parameters or a regenerated full definition

#### Scenario: Strict output is unsupported or interrupted
- **WHEN** the provider rejects the projected strict schema, refuses or truncates its response
- **THEN** a typed failure is recorded under the shared budget without silent model switching or retrying as JSON_TEXT; a JSON_TEXT profile must have been explicitly selected by capability policy

#### Scenario: Valid command has incorrect targets
- **WHEN** operations conform to schema but select history charts instead of the requested current cards
- **THEN** the operation writer's target mask is not sufficient evidence and the candidate passes the separate V intent/outcome boundary before it can become READY

### Requirement: Reviewed domain skill execution
Assistant plans SHALL use existing authorized domain preparation/execution and receipt boundaries, with separate immutable confirmations for new mutating payloads and exact legacy review compatibility.

#### Scenario: Device then source then dashboard
- **WHEN** all required prior steps succeed
- **THEN** dashboard context uses actual created identities and reads current capabilities before composition

#### Scenario: Stored composite HSL review
- **WHEN** a legacy accepted review is executed after migration
- **THEN** its original reviewed payload is used without replanning or a second source mutation

#### Scenario: Unimplemented connector
- **WHEN** the user requests a connection absent from installed capability contracts
- **THEN** the assistant reports the limitation or stays on an explicit supported workflow rather than inventing a connector

### Requirement: Deterministic targeted edits and workflow parity
Domain skills SHALL preserve existing supported scenarios and lower model-selected bounded operations through deterministic compilation and independent outcome verification, without semantic regex or whole-definition regeneration.

#### Scenario: Move three widgets
- **WHEN** the model proposes a move of three current-value widgets
- **THEN** server layout preserves other bindings/settings/relative order and verification rejects collateral changes

#### Scenario: Refine before creation
- **WHEN** the user moves a map in an unexecuted dashboard review
- **THEN** the assistant prepares a new immutable review without creating or updating a dashboard until explicit versioned confirmation

#### Scenario: Edit after creation
- **WHEN** the user requests a resize in an actual previously created dashboard
- **THEN** the assistant loads its current draft and revision, prepares a reviewed UPDATE_DASHBOARD_DRAFT and updates the same dashboardId only after confirmation and revision validation rather than creating a copy

#### Scenario: Ordinary non-HSL device
- **WHEN** the user creates a registered custom type with optional fields omitted
- **THEN** the workflow remains supported and is not coerced into HSL or blocked by invented requirements

#### Scenario: Unmigrated tool
- **WHEN** a supported legacy simulator or rule workflow is not yet v2-qualified
- **THEN** it remains explicitly available through compatibility handling and is not silently misrouted

### Requirement: Separate creation and edit proposals
The runtime SHALL expose separate versioned creation, unexecuted review refinement and persisted draft update skills, while reusing the existing domain compilers and reviewed execution. Planner input bindings SHALL contain only declared resource fields, leaving domain interpretation to the selected skill.

#### Scenario: Initial creation contains local widget references
- **WHEN** CREATE_DASHBOARD prepares a new dashboard
- **THEN** its model response omits widgetKey entirely, supplies exactly one dashboard name operation and addresses newly created widgets by backward local references; validated operations lower to the existing internal protocol

#### Scenario: Unknown key in an edit
- **WHEN** a dashboard edit targets a key absent from the current accepted snapshot or a forward local reference
- **THEN** server validation rejects the proposal before owning preparation without treating the unknown key as a new widget

#### Scenario: Replace a failed dashboard step
- **WHEN** the planner replaces a failed or pending dashboard step
- **THEN** its context provides actual dependencies and goal coverage; the graph rejects dependencies on superseded steps and retains the accepted review as context without retaining an executable binding for the replaced step

#### Scenario: Another new dashboard in the same conversation
- **WHEN** the selected skill is initial creation and no dashboard review is explicitly selected as its base
- **THEN** ambient review focus does not become an inherited dashboard definition, while confirmed device identities and mandatory dialogue remain available

### Requirement: User-controlled design discretion
The dashboard skill SHALL follow explicit user requirements exactly and independently choose only unspecified aspects from actual data and supported capabilities, without predefined device templates or a mandatory widget bundle.

#### Scenario: Fully delegated design
- **WHEN** the user asks the assistant to choose a useful dashboard for the selected device
- **THEN** the model selects metrics, representations and hierarchy from actual types/formats/units/views and acceptance permits different coherent compatible compositions

#### Scenario: Exact content and delegated appearance
- **WHEN** the user fixes metrics/views but delegates size, labels and layout
- **THEN** the model preserves explicit content and chooses only delegated aspects; unrelated metrics or omitted representations are not justified by design discretion

#### Scenario: New edit follows accepted creation
- **WHEN** one creation step awaits confirmation and the user submits a new modification
- **THEN** planner routes the request to an edit skill; only a real pending-question answer bypasses routing

### Requirement: Grounded planner reference projection
The primary planner SHALL select resource references from the current authorized
snapshot rather than generate literal resource identifiers. The server SHALL
lower these references and attach current-message provenance to newly proposed
goals before unchanged canonical, registry and graph validation. This projection
SHALL be versioned and pinned, without semantic fallback or another call budget.

#### Scenario: Selected resources are bound
- **WHEN** the planner selects one or more supplied device references
- **THEN** the server supplies the exact corresponding scalar deviceId or deviceIds array; unknown references, mismatched resource kinds and forged source provenance are rejected rather than guessed

#### Scenario: Planner asks for a domain parameter
- **WHEN** the planner emits a question field such as a dashboard name instead of intent or target routing ambiguity
- **THEN** the proposal is rejected within the existing bounded correction boundary; the selected domain skill remains responsible for domain questions

#### Scenario: Accepted dashboard generation is continued
- **WHEN** the planner chooses CONTINUE on a dashboard step already waiting for confirmation
- **THEN** it receives a typed requirement to AMEND using the selected review's requiredIntent, before another domain generation call; the accepted variant and confirmation rules are preserved

#### Scenario: A successful normalized call is replayed
- **WHEN** the shared ledger already contains the successful canonical response
- **THEN** replay validates that response without lowering its resource identities again or invoking the model; failed lowering retains actual usage in the same ledger

#### Scenario: Context devices accompany an active dashboard
- **WHEN** the persisted focus names an exact supplied dashboard review and selected devices provide its context
- **THEN** the wire projection explicitly marks that review as the active subject and the devices as context resources, without inferring intent from user prose or inventing a subject when the focus does not match

#### Scenario: A review reference is used as a device binding
- **WHEN** a planner response binds a dashboard-review reference to deviceId or invents a planner input field
- **THEN** the model-facing schema rejects the reference/field combination; read-only review context is not advertised as an eligible device/source binding

#### Scenario: A short follow-up omits the established subject
- **WHEN** the user supplies a short change after a selected dashboard review
- **THEN** the planner instructions require resolving its meaning from exact text, mandatory dialogue and the active subject before asking; already supplied values and merely hypothetical alternative objects do not justify a question, while explicit new workflows remain available

### Requirement: Pure prompt assembly and specialist integration

Domain skills SHALL preserve the single durable runtime and keep prompt serialization/estimation free of network I/O. Nested consultation and knowledge retrieval SHALL use versioned registered ports, not recursive ChatClient tool callbacks or an independent memory/ledger.

#### Scenario: Consultation and retrieval boundary
- **WHEN** semantic capability search needs an embedding or a child specialist needs another call
- **THEN** the external operation receives its own prior reservation and authorized context before a pure prompt is assembled

### Requirement: Selective consultation and mandatory exact context

The assistant SHALL prefer exact loaders and a qualified domain skill before delegating, preserve mandatory question/resource/contract context and keep domain questions owned by registered skills. Private retrieval SHALL authorize candidate IDs before text hydration.

#### Scenario: Revision two dependency and quality boundary
- **WHEN** context is oversized or a default VectorStore returns private text before access verification
- **THEN** the mandatory context is retained or CONTEXT_INSUFFICIENT is returned; the unsafe retrieval path cannot dispatch private content

### Requirement: Spring framework extension boundaries

The assistant SHALL reuse Spring AI provider/models/tools and Spring transactions, executors and observations through explicit application ports. Custom Advisors SHALL validate an immutable prepared exchange and an already reserved dispatch without hidden loops; the observation aspect SHALL NOT own budget, authorization or effects.

The qualified LangGraph4j runtime SHALL own new graph traversal under adopt-langgraph4j-agent-orchestration. Domain skills, canonical validators and outcome/recovery policies SHALL retain their existing authority and SHALL NOT create a competing traversal or unreserved model loop.

#### Scenario: Spring extension boundary
- **WHEN** structured-output validation could trigger an SDK retry or a manually built client lacks the Boot registry
- **THEN** the proposal profile prevents automatic repeats and explicitly wires the managed registry while preserving the existing durable ledger and completed migration
