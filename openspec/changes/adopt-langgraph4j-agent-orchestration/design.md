# Design: One Java graph runtime with existing domain authority

## Decision and scope

LangGraph4j core1.9.9 passed the executable compatibility gate for the implemented
core slice. Keep Spring AI for provider exchanges. Prefer the smallest core graph
dependency with application nodes calling the existing Spring AI exchange port.
Use a framework integration module only if its pinned dependency tree and call
semantics pass the same checks. Do not use a stock agent executor that opens an
unaccounted model/tool loop. The implementation pins core1.9.9; the evidence and
remaining boundaries are recorded below and in the current architecture.

Official sources inspected on 2026-11-11:
- [Project and core/checkpoint concepts](https://github.com/langgraph4j/langgraph4j).
- [Upstream parent POM](https://github.com/langgraph4j/langgraph4j/blob/main/pom.xml).

The mutable upstream parent inspected at planning time declares Spring AI 2.0.1;
our baseline is 2.0.2. This is a reason to test a released artifact and record its
source tag/dependency tree, not proof of compatibility or an instruction to downgrade.

## Replacement and retained responsibilities

| Concern | Owner after migration |
| --- | --- |
| Graph nodes, edges, conditional transitions, bounded traversal and subgraphs | Qualified LangGraph4j runtime adapter |
| Goals, requirement revisions, typed skill inputs and question policy | Existing application/domain contracts, extended by G7 |
| Provider requests, options, schema/tools and observed usage | Existing Spring AI exchange path |
| Budget reservation, dispatch identity and unknown remote outcomes | Existing A2 ledger and explicit dispatch boundary |
| Access, review/version/digest, confirmation and owner effects | Existing application/owner contracts |
| Conversation history, resource identity and successful receipts | Existing authoritative stores; no alternate ChatMemory |
| Domain outcome verification and bounded repair decisions | Existing V/R owners through the same graph and ledger |

An empty StateGraph wrapper around the unchanged handwritten traversal does not
satisfy the change. Inventory transitions owned by IntentOrchestration and
dependent consultation scheduling, map them to library transitions, then remove
or retire the replaced traversal once compatibility is proven. Domain validators
and receipt/review state are legitimate application code and remain explicit.

## State and checkpoint contract

Use typed, versioned graph state with account/conversation/plan IDs, revision,
graph-definition version, current node and references to requirements/reviews/
receipts. Do not serialize provider clients, credentials or private reasoning.
Existing PostgreSQL records stay authoritative for plans, reviews and effects.

The spike must choose and demonstrate a single-authority checkpoint strategy:
an adapter using the existing transaction boundary, or a recoverable projection
with atomic intent/outbox plus reconciliation. A generic PostgreSQL saver is a
candidate, not presumed to share application transactions or provide fencing.
If a projection is used, it cannot independently authorize dispatch/effects.
Document commit ordering, revision/CAS checks, leases, migration and restart
reconciliation before production wiring. Resume must freshly authorize the
actor and validate graph/version/revision; old checkpoints grant no permission.

Crash between model send and persisted response remains UNCERTAIN under the
existing ledger. Graph replay must not blindly resend it. Owner mutations use
original keys/receipts; checkpoint replay is not exactly-once remote inference.

## First vertical slice

The graph is the execution graph, not the user plan's dependency graph. Preserve
A3's acyclic typed step dependencies and receipt bindings. Library cycles only
represent bounded clarification/context/recovery control flow; they never permit
a cyclic domain dependency or resetting a turn/plan budget.

Start with a frozen, supported scenario: authorized existing device, dashboard
creation, one clarification, immutable preview/confirmation and exact saved
readback; then a same-ID dashboard edit and restart/replay. Add a controlled
device/source dependency only after its actual capability/receipt contract is
available. Native table authoring is an additional existing slice, not a reason
to finish the entire configurable platform first.

Graph roles: coordinator -> scoped context/skill -> canonical validation ->
review -> wait for explicit confirmation -> owner apply -> receipt/readback.
Question/confirmation waits are persisted pauses, not blocked request threads.
G7 specialists extend this through bounded subgraphs/consultation nodes, one
root question arbiter, shared budgets and cancellation/revision fences. Do not
hardcode free-form intent routing in graph edges; models select typed intents,
and deterministic code routes validated protocol outcomes.

## Rollout and qualification

There is no reverse dependency from graph adoption to completion/archive of A5,
A6 or all G7 tasks. Task3.2 checks applicable integration predicates, not those
entire changes. G7/100.1–100.2 and applicable101.* supply requirements/consultation
semantics; G7/100.3 RAG, generic configurable operations and unrelated acceptance
remain separate. A5's original first-pass checkpoint before final V/R acceptance
is unchanged. Offline graph/V/R integration cannot substitute for that live
checkpoint or close A5 when its authorized evidence is absent.

| Task owner | Reused evidence / remaining authority |
| --- | --- |
| This change1.*–2.3 | Compatibility, actual traversal replacement, checkpoint/dispatch recovery |
| G7/100.1–100.2, applicable101.*, this change2.4 | Product requirements/question semantics in G7; library subgraph mechanism here; one implementation, distinct criteria |
| G7/102.2 | Bounded worker wakeup, lease/heartbeat and durable recovery around the same library runtime; no independent graph traversal |
| A3/1.9 and A5 | API/frontend recovery and domain parity using the new engine; checked historical implementation is not undone |
| V/R | Independent outcome checks and repair policy; graph invokes them, not a second verifier/ledger |
| A6 | Separate real-model/economic/portability qualification; graph fixtures never close it |

Run this change3.1 after2.1–2.3; it need not wait for specialist subgraphs2.4.
Specialists follow the demonstrated basic workflow before final cutover checks.

First run a real library graph with deterministic model/owner fixtures, including
PostgreSQL restart, concurrent resume, stale/cancelled work and uncertain I/O.
Record first-pass, recovered and final outcomes independently. Paid/live model
qualification remains a separately authorized A6 activity. Library correctness
does not imply semantic model quality.

Keep external APIs, old conversation reads and saved reviewed execution working.
A versioned default-off graph profile may coexist for migration, but each plan
has one pinned engine version. An in-flight plan cannot silently switch engines
or fall back on error. Provide explicit migration/reconcile and rollback rules;
keep legacy read/receipt reconciliation without perpetuating two generators.

## Implementation decision and evidence — 2026-11-11

Adopt core1.9.9 only. Its new runtime dependencies are async-generator5.2.0
and BOM-managed SLF4J; both core and async-generator declare MIT licensing.
No Spring AI integration module, model SDK, graph database or general saver
is imported. The isolated dependency tree retains Boot4.0.9, Spring AI2.0.2
and SLF4J2.0.20 without platform downgrade. Source release:
[v1.9.9](https://github.com/langgraph4j/langgraph4j/tree/v1.9.9), peeled commit
`645aa68af1119be5043e9a016a783c6b55bb5778`. Maven Central core JAR SHA-256:
`5dc727b442dcac5dd2b0a778acccd30648beccbf05dd4e8a34cfeddb690abd30`.

The chosen checkpoint strategy is atomic plan storage, not a projection or a
separate saver database. Request begin pins the engine in schema2. Each native
checkpoint is converted to a primitive-only GraphExecution and committed together
with plan state through the existing conversation lock, request lease and revision
fence. The last checkpoint commits reply/request completion in that same short
transaction. External model/owner calls run outside transactions. A failed turn
may retain its last diagnostic cursor; no endpoint uses that cursor as permission
to resume remote I/O. Duplicate/abandoned request IDs replay stored state without
model calls. A changed engine still permits exact stored-request replay, but not
new generation. Legacy records are not backfilled.

Replaced code: IntentOrchestration's recursive decide/prepare/accept/repair
transitions, RoutedIntentModels' context-continuation loop and confirmation's
linear dispatch/readback traversal. Node handlers still own domain validation;
the LangGraphAgentRunner adapter owns actual StateGraph traversal. Deterministic
cancel/retry controls and receipt reconciliation remain application use cases,
not a second generated-plan scheduler.

Validation used Temurin21.0.12.1, including actual PostgreSQL16 Testcontainers:
- Dependency-isolated Maven spike: one library compatibility test passes,
  including bounded subgraph, checkpoint pause and explicit resume.
- Full AI-service/dependency reactor:315 AI tests pass, zero failures/errors/skips;
  shared-module tests pass as well.
- A subsequent stored-request replay guard adjustment and concurrent-resume
  cases pass30 focused tests on the final source, including SDK HTTP fixtures,
  context/repair budgets, question restart, atomic rollback and PostgreSQL races.
- The serial emitter runs on the caller thread; a test verifies thread identity.
  Library exception envelopes are unwrapped to original application failures;
  failing nodes run once, and the traversal is bounded at32 transitions.

Ignored evidence: artifacts/langgraph4j-java21-tests.log,
langgraph4j-java21-final.log, langgraph4j-isolated-spike.log,
langgraph4j-spike-dependencies.txt and the isolated spike source/POM. These
fixtures make no real-model calls and do not qualify A5/A6 semantics or billing.

At the2026-11-11 checkpoint, still open:3.1 exact ordinary-API dashboard lifecycle/readback,2.4 G7 specialist
semantics and qualified nested checkpoint namespaces,3.2 full applicable UI/
owner acceptance,3.3 reviewed in-place migration/rollback and3.4 final whole-change
validation after those tasks. Core validation here does not close these tasks.
See [current runtime](../../../docs/current/architecture/spring-ai-assistant.md#langgraph4j-core-integration--2026-11-11)
and [ADR-015](../../../docs/adr/ADR-015-library-backed-agent-graph.md).

## Ordinary-API Docker lifecycle evidence — 2026-11-12

Task3.1 is now verified by `scripts/ai-langgraph-workflow-docker-smoke.py`
against the actual application Compose stack. An isolated synthetic account
selects a disposable OpenAI-compatible provider; Spring AI and the actual
LangGraph runtime call it through the normal reservation/exchange path. A real
vehicle/source and recorded telemetry are prepared through ordinary APIs.

The successful second run passes13 checks: persisted clarification, exact
question after restart, turn replay with no model I/O, stale confirmation denial,
confirmed creation/exact readback, original receipt reuse after another restart,
partial planning failure preserving the created draft, independent name-only
preservation oracle, same-ID update/exact readback, update receipt replay and
exactly one dashboard in the isolated inventory. All7 provider exchanges are
deterministic fixture calls; real-model calls and model-judge calls are zero.
Cleanup archives the owned dashboard, decommissions the owned device and deletes
the temporary provider. Demo-account provider/model settings are not modified.

The first run is retained as FAILED: its negative fixture used `locations`
instead of canonical Diagnostic.fieldPaths. Server validation correctly rejected
it and its unexpected format-correction call was not allowed to rescue the run.
Only the fixture envelope was fixed; production code, acceptance criteria and
historical model scores were unchanged. Both runs completed fixture cleanup.

Ignored evidence: artifacts/langgraph4j-docker-workflow/summary.json (first failed
run), artifacts/langgraph4j-docker-workflow/attempt02/summary.json (13/13 passed),
matching run logs and startup/health summaries. Reports retain exact source and
harness fingerprints, synthetic definitions and typed outcomes without credentials.
Application and observability profiles remain running; original volumes are
preserved. Optional HSL/Home Assistant profiles are not part of this proof.

Next:2.4 bounded specialist semantics through the same graph. Tasks3.2/3.3/3.4,
A5/A6 quality/default gates and explicit in-place migration remain open. The
successful deterministic lifecycle does not qualify a live model or UI behavior.

## Specialist implementation checkpoint — 2026-11-12

Task2.4 now has an internal collaboration implementation: typed requirement
proposals, user values/choice answers, dependency invalidation, one question
arbiter, consultation admission/claim/acceptance/cancellation and a plan-owned
JSON journal. V011 stores the snapshot and append-only revision snapshots/digests
atomically. The adapter uses conversation -> plan locks, root turn/revision/lease
fences, database time, immutable pins/counters and idempotent request keys.
Deadlines are bounded by the original plan origin/A2 deadline, never a restart.

Native compiled `StateGraph` subgraphs execute without independent savers through
`AgentGraphRunner.runTurn`. The worker's claim/guard/exchange/accept nodes use
durable task attempts plus the original A2 ledger. The additive CONSULT phase
uses `RoutedModelCalls` and the existing Spring AI transport. Existing primary
policies, version digests and generation defaults are unchanged. Parallel workers
must share the same reservation; a duplicate cannot complete another worker's
STARTED attempt. An uncertain exchange retains its reservation and records
UNKNOWN_OUTCOME without retry.

Pure policy checks cover depth3, fan-out3, nodes12, RUNNING2 and exchanges2 per
obligation, declared capabilities/evidence/assignment echoes and stale/rebase
read-set handling. The root arbiter merges duplicate unresolved questions and
marks contradictory choices CONFLICT without voting. Canonical JSON null/numeric
round trips are tested rather than treating Java-only object equality as durable
state equality. Root checkpoints remain primitive-only; this task journal is
not an alternative inference/effect authority.

The task stays open. Remaining work includes production role/type/owner-evidence
registration; typed free-text answer handling and registered derivations;
root-loader/context and confirmation invalidation integration; bounded durable
executor/continuation admission; public requirements/inspection/cancellation APIs,
UI and exact owner end-to-end tests. Internal worker coverage does not qualify
these paths. Independent child saver namespaces remain deliberately disabled;
no raw child checkpoint can resume inference. Tasks3.2–3.4 and the original
A5/A6/model-quality gates remain open.

Verification for this checkpoint uses Docker Temurin21.0.12.1: the full
AI-service/dependency reactor passes349 AI tests with zero failures/errors/skips,
followed by44 focused graph/protocol/consultation/transport cases. The final
reservation-conflict guard and post-lock expiry test pass11 subsequent focused
cases, including seven PostgreSQL cases and three Spring AI output modes against
an in-process HTTP fixture. These counts are separate overlapping runs, not a
combined test total. Strict OpenSpec passes105 items; structure and AI contract
checks pass. Existing oversized-service/wildcard warnings are retained.

Ignored evidence: `artifacts/langgraph4j-collaboration-java21-reactor.log`,
`langgraph4j-collaboration-java21-final.log`,
`langgraph4j-collaboration-java21-races.log` and
`artifacts/langgraph4j-collaboration/source-manifest.json` (26 source/contract
fingerprints). Real-model and model-judge calls remain zero.

The updated Compose stack applied V011 successfully. Its ordinary-API lifecycle
rerun passes13/13 checks, including both restarts and same-ID editing, with7 local
fixture exchanges, zero real-model calls and completed fixture cleanup. Final
postflight records25 running application/observability containers and12/12 HTTP
checks; all26 source fingerprints still match. Evidence is under
`artifacts/langgraph4j-collaboration/docker-workflow/attempt02/summary.json` and
`artifacts/langgraph4j-collaboration/health.json`.

The earlier checkpoint preflight report is retained at
`artifacts/langgraph4j-collaboration/docker-workflow/summary.json`: it failed503
while the external restart was still in progress, before any fixture provider
exchange. The rerun waited for the completed restart/fresh service readiness and
uses the same source/harness, without changing runtime or acceptance criteria.

## User-requested stop and resumption point — 2026-11-12

The user requested a logical stopping point and documentation, then cleared the
active execution goal. Work stops at the verified internal specialist runtime
checkpoint above. All26 implementation/contract fingerprints still match the
successful test and Docker evidence. The subsequent continuation inspected the
primary assistant and requirements contracts only; no new endpoint, DTO or
partially wired production feature was added. The change is paused, not completed
or archived. Task totals remain8 checked and4 open.

The current public assistant still uses the qualified core graph. The new
consultation worker, requirement policy and journal remain internal components;
they do not yet provide public requirements/consultation routes or UI. Existing
default-off gates and provider settings are unchanged. The Docker stack remains
running; stopping development does not stop or remove containers or volumes.

On an explicit resume, continue task2.4 at the production requirements/consultation
boundary. First establish and test one atomic transition for the requirements
snapshot, root plan revision/question and affected review invalidation. Preserve
confirmed facts/receipts and fence late workers/confirmations. The current
`JdbcCollaborationStore.update` uses REQUIRES_NEW: do not call it while holding
root locks in another transaction or introduce independent commits that can
leave a changed requirement paired with an executable old review.

Then wire the exact revision-bound CHOICE/TEXT/VALUES answer contract, verified
user-message provenance, registered slot types/derivations and authorized replay.
Continue with primary role/loader registration, bounded durable execution and
applicable continuation, cancellation, API and UI acceptance. Tasks3.2–3.4 retain
their full scope, including reviewed migration/rollback and final backend/frontend
validation; existing fixture passes do not close them or the A5/A6 quality gates.

## Atomic requirements and answer binding — 2026-11-13

`JdbcIntentPlanStore` owns the transaction for the requirements transition.
It reuses the collaboration CAS and journal under the conversation and root
locks and revokes the explicitly affected READY reviews in the same transaction.
The root commit advances the revision, closes the request, and journals the
question or reply. Completed steps, facts and receipts retain their authority.
Replay checks the owned request, message and bound digest before returning the
current plan. A late root token cannot commit over the new revision.

`RequirementAnswers` validates the target wire shape and routes CHOICE, TEXT
and VALUES through the revision-checked slot policy. The JDBC answer adapter
binds the answer to the digest of the journaled user input and uses its message
ID for provenance. TEXT confirmation is limited to one eligible slot with no
choices and passes through the supplied type registry. The request metadata
omits the answer body.

H2 and PostgreSQL fixtures cover rollback after a journal insert failure,
successful root/question commit, persisted review revocation, completed
request replay, late-root rejection and typed answer provenance.

Remaining work includes the production slot registry and derivations, affected
step binding and graph continuation, role/loader registration, durable
consultation execution and the public API/UI. The reviewed migration and final
acceptance gates remain open.
