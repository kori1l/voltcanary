# AI task boundary and execution: design

## Existing behavior

AiAgentController validates a user JWT. ToolExecutor forwards Authorization to
domain APIs. LlmPlanSanitizer allowlists names/parameters, but untyped maps and
fallback merging can produce invented defaults. PlanStore is process-local;
orchestrator checks only userId and repeats steps on every execute. Model-run
recording exists separately; action audit is log-only. Preserve useful adapters
and review UX, extract focused workflow components from planner/executor.

## Scope and trusted context

Resolve current user and actual account using existing authenticated contracts;
do not treat aiagent.AuthenticatedUser.accountId()==userId as a universal account
mapping. New creation workflows target personal resources only. No automatic
switch to shared workspace, publication, invitations, grants or admin tools.
If an account has not been initialized, return ACCOUNT_SETUP_REQUIRED and use
the normal explicit account setup flow before preparing a scoped task; do not
substitute userId or auto-provision an account during context inspection.
Existing explicit workspace read functionality outside these workflows is not
silently expanded or removed. Invalid account state fails in owning services.

Browser context, user prose and model output are untrusted. Server rebuilds a
bounded catalogue using user-authorized APIs. Refs are random/task-local opaque
references to verified resource IDs, not security credentials. Revalidate all
resolved IDs through owning services at execution, including refs from old plans.
Raw UUID candidates in prose cannot bypass lookup checks. Filter before provider
submission, not after generation. Enforce recursive type/size/depth limits,
secret redaction and data minimization server-side; never expose JWT, source
tokens or provider keys. Treat resource names as data, not instructions.

## API and state

Retain POST /ai-agent/plans and POST /ai-agent/plans/{id}/execute. Add scoped
GET /ai-agent/plans/{id} for status/recovery. Extend response additively with
version, expiresAt, planningStatus, questions and execution state; zero actions
with NEEDS_INPUT is valid and cannot execute. Existing action/status consumers
must be adapted deliberately, not assume every response is executable.

Store plans, sanitized context, reference mappings, normalized payload digest,
owner user/account, versions and action results in AI's own schema. A confirmed
execution includes expectedVersion/digest; legacy callers may execute a loaded
immutable version only, never a mutable plan selected by a bare boolean.
New clients submit the reviewed version explicitly. Changes produce a new
version and invalidate confirmation. Expired plans require renewed preparation.
Retention is bounded: default plan expiry 24h and sanitized terminal-result
retention 30d, configurable; references/secrets are excluded from public logs.
Keep deduplication tombstones for at least the owning API's retry retention.

Atomically claim execution across replicas. Persist per-step states READY,
RUNNING, SUCCESS, FAILED, UNKNOWN; task exposes READY/RUNNING/SUCCESS/FAILED/
PARTIAL_SUCCESS/UNKNOWN/EXPIRED and NEEDS_INPUT planning status. Durable audit
links user/account, plan version, action, model run and resulting resource IDs.
No raw JWT is persisted for restart/resume; caller supplies a current JWT.

Idempotency keys are stable per user/account/plan-version/action and forwarded
to owning mutation APIs. Domain outcome idempotency is mandatory in feature
changes; AI DB state alone cannot fix create-committed/response-lost crashes.
On ambiguous timeout or expired execution lease, reconcile through idempotent
API before retry. No blind rollback/delete or new key to hide unknown outcome.
Replay returns recorded results only after fresh caller/account checks; serving
a result does not expose foreign resource data after access changes.

## Small-model contract

Separate intent extraction, typed validation, clarification and deterministic
workflow compilation. Domain changes own their concrete DTOs and API checks.
No arbitrary URL, headers, SQL, code, tenant or role fields. Provider JSON schema
support is an optimization; Java validation remains authoritative. Allow at most
one repair attempt for malformed output, then NEEDS_INPUT/UNSUPPORTED; never
silently turn unsupported prose into unrelated resource creation. Defaults only
from explicit UI choices or documented domain/template values. Retain legacy
tool behavior behind its existing explicit request/review path and same task
isolation; no deletion/publication added by this program.
Creation requests routed through legacy tool names must enter the same typed
compiler and review checks once each new workflow ships; retaining a generic
legacy route must not bypass schema validation or execution idempotency.

## Verification and dependencies

Two users/accounts across plan create/read/execute, provider/context refs,
cache and results. Test prompt-injected names, forged context, changed account,
revoked refs, parallel execute, restart, expiry, timeout-after-commit and stale
confirmation. Existing domain denial is the authority; preflight is advisory,
read-only and never calls an auto-provisioning account lookup. No mandatory
canCreate API or new role catalogue. Run AI backend tests with real PostgreSQL,
frontend checks/browser acceptance and full backend when common modules change.

