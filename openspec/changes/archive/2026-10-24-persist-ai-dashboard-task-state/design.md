# Design: Dashboard task state separate from execution reviews

## Current code and delta
`PlanStore` persists immutable `ai_reviewed_plans`, action receipts/status and audit
using JDBC. `PlanClarifications.remember` embeds continuation maps in context_json;
`CreationTaskPlanning` collects names and pending failures; `DashboardTaskName`
extracts some explicit name replies; `DashboardReviewHistory` restores stored
definitions. Extend these seams and retire redundant map ownership gradually.
Do not rewrite historical plans or reset execution states to enable retries.

## Domain model
Add immutable typed domain values (no RestClient/JDBC dependencies):
- `DashboardTaskState`: schemaVersion, taskId, branchId, revision, originalRequest,
  explicit facts, selection preferences, lastAcceptedReviewId and bounded pending
  changes. Persist stable resource identities as preferences; renew model refs.
- `TaskFact`: value, provenance USER_EXPLICIT/USER_ACCEPTED_DEFAULT, originating
  turnId and accepted revision. Model-proposed defaults remain proposals until an
  actual manual or labelled automatic user reply accepts them.
- `DashboardTurn`: turnId, idempotent requestId, parent/base review, revision,
  raw user message, extracted fact patch, lifecycle and safe failure category.
- `PendingChange`: changeId, originating turn, request, structured operations when
  available, and PENDING/APPLIED/FAILED/SUPERSEDED disposition. A failed/no-op
  candidate cannot mark pending work APPLIED.

Use the current input guard limits (reply2000,30 clarification rounds, bounded
context and widget/device counts); do not make a hidden unlimited transcript.
Keep the bounded original request separately from a model-context summary. If
the context budget is exceeded, ask to narrow/split work without erasing facts.

## Persistence and concurrency
Add the next Flyway migration after V002 in ai-agent-service for owner-scoped
task/branch state and turn records. Provide an outbound persistence port and JDBC
adapter; retain `PlanStore` as the reviewed-plan authority. Index actor/account/
task/branch and unique request identity. Share expiry/retention policy with tasks;
purge turn/branch data without orphaned references after the retention window.

Accept a turn atomically against expected revision and its explicit base review.
Persist PENDING before a model call, release the DB transaction, perform remote
work, then commit the outcome with compare-and-set. Never hold a database lock
while waiting on a provider. Duplicate requestId/payload returns the same turn;
same requestId/different payload conflicts. A lease/restart recovery records an
interrupted turn without inventing a result or invoking domain execution.

Two concurrent turns against one branch cannot silently replace each other.
Return409 for a conflicting write; deliberate editing from an older authorized
review starts an explicit branch with that exact reviewed definition. Frontend
Back/Forward GET navigation itself remains read-only and invokes no model.
lastAcceptedReviewId changes only on accepted candidate persistence. A failed
turn advances its task ledger revision but leaves that review unchanged.

## Fact handling
Keep the original user message even if interpretation fails. A structured fact
patch is validated for field bounds and grounded in the user's turn or explicitly
accepted suggestion, then saved before compilation. The existing explicit-name
helper may support legacy replies, but do not expand a phrase/regex catalogue as
the new architectural interface. Unsupported/ambiguous interpretations cannot
overwrite a confirmed fact. A new explicit rename replaces the old name; technical
failure in other operations does not roll that confirmed preference back.

Task kind is stored explicitly after dashboard routing. Later short messages
such as `сделай меньше` continue that task without needing a dashboard keyword.
Prior assistant explanations and candidate resources never become accepted facts.
Pending work remains visible to the planner until fulfilled or explicitly cancelled/
superseded; changing one request does not silently discard the rest.

## API and frontend compatibility
Keep existing POST `/ai-agent/plans`, POST `/plans/{id}/clarifications`, GET
`/plans/{id}` and confirmed `/execute`. Define additive request metadata
`requestId`, `expectedTaskRevision` and `baseReviewId` (base must match the selected
authorized plan); introduce a typed safe `task` summary in responses with taskId,
branchId, revision, lastAcceptedReviewId, pending status and recoverable error.
Document exact optional-field rollout before enabling the new frontend.

For a failed initial turn that has no review yet, provide owner-scoped
GET `/ai-agent/dashboard-tasks/{taskId}` recovery returning the safe summary,
not context_json or credentials. GET performs no mutation or planning.
Legacy callers that omit metadata retain the old immutable-parent branching path;
the server supplies a scoped request identity per request but cannot promise
network retry deduplication without a client requestId. Do not pretend otherwise.

Use `frontend/src/aiAgent/aiAgentApi.ts`, `useAssistantConversation.ts`, existing
history controls and task recovery storage. Keep requestId stable for a network
retry, invalidate confirmations on review/identity change and show prior preview
with an honest failure/pending notice. Avoid a parallel frontend task-state store.

## Compatibility, authorization and rollout
Read legacy `_clarification.input` through one bounded adapter and map it to the
typed model on first authorized continuation; never backfill by contacting a model.
Do not migrate RUNNING/SUCCESS/UNKNOWN execution tasks into editable state. Foreign
task reads404, expired task410 and revision/invalid-base conflicts409. Selected
resources are reauthorized for each planning turn, restore and execution. A revoked
selection is retained as a preference with unavailable status, not as permission.
Roll out state writes/read compatibility before enabling new request metadata.
Rollback leaves old immutable reviews executable and does not drop new tables.

## Verification and basis
Use PostgreSQL Testcontainers for restart, concurrent writers, lost responses,
transaction rollback, legacy upgrade, expiry and two-account isolation. Add browser
reload/branch and real-name failure recovery cases to the shared evaluation corpus.
Check explicit fact precedence with deterministic tests, including unquoted Cyrillic.
[LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
illustrates durable checkpoints; this design uses the existing Java/PostgreSQL
architecture and does not require adopting LangGraph.
