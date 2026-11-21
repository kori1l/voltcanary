# Implementation and verification

2026-11-11: task1.9 is checked against the
[library-backed slice](../archive/2026-11-13-adopt-langgraph4j-agent-orchestration/tasks.md) under
[AI-first priority](../README.md#ai-first-execution-priority). Existing checked
tasks remain historical baseline; no second handwritten graph traversal is
required or authorized by them. Full G0–G4 is not a prerequisite for1.9.

- [x] 1.1 Implement bounded planner requests/decisions without semantic regex; let known-step replies use its skill directly, with model-returned REPLAN_REQUIRED instead of a mandatory router call on every reply.
- [x] 1.2 Add additive migrations/ports for scoped versioned plans, steps, goal references, facts, receipt bindings, pending question IDs and focus/resource chronology; reuse conversation identity and retain resource links across completed plans.
- [x] 1.3 Validate unique step IDs, registered intents, dependency cycles, output/input types, forward dependencies and bounded plan size.
- [x] 1.4 Implement PLAN/CONTINUE/AMEND/ASK/UNSUPPORTED, explicit supersession and typed source-message provenance for retained requirements.
- [x] 1.5 Resolve fact_ref/step_output only from current authorized confirmed results; proposed/failed/unknown creation never creates a resource fact.
- [x] 1.6 Implement revisioned controls and per-review confirmation, partial success, deleted-resource failure and UNKNOWN receipt reconciliation.
- [x] 1.7 Preserve request deduplication, restart/cancel/late-response behavior, two-account isolation and read-only recovery without model calls.
- [x] 1.8 Add multilingual/paraphrase, multi-intent, two-device, optional-field, unknown-intent and no-preconfirmation-mutation regressions.
- [ ] 1.9 Align conversation/plan APIs and frontend recovery during implementation; run PostgreSQL, frontend and strict repository checks.

## Evidence - 2026-10-26

Implemented A3 application/domain/persistence/receipt boundaries under
`orchestration.plan`, additive V008, owner-scoped read-only recovery routes and
shared-trace/A2 model integration. Existing conversation messages remain the
source of raw text; actual reviews/action receipts remain execution authority.
No semantic regex/keyword/question-text routing was added. Generation and mutation
HTTP writers are not enabled; A4 request assembly and A5 domain compilers remain
separate owners. Legacy UI/history/reviews/receipts continue to work.

Narrow command: `cd backend; mvn -pl services/ai-agent-service -am
-Dtest=IntentWorkflowScenarioTest,IntentPlanningSafetyScenarioTest
-Dsurefire.failIfNoSpecifiedTests=false test`. Two offline scenarios pass using
actual H2 migration/JDBC/review persistence and the existing receipt adapter;
provider/domain transport is simulated, not language-quality evidence. Scenario 1
covers dependent create/connect/dashboard work, exact question/reload/short reply,
no preconfirmation mutation, failed connection with the created device retained,
UNKNOWN reconciliation without another create, cross-plan identity, deleted
selection and account isolation. Scenario 2 uses actual A2 routing/ledger with
model fixtures for two devices, explicit amendment, review revocation, duplicate
requests/restart, same-budget REPLAN_REQUIRED, in-flight cancel/late output, graph
cycles/forward references/type mismatches, unknown intent and unconfirmed facts.

`npm run structure:check` passes with existing risk warnings. Strict OpenSpec
validation passes 86/86 after documentation alignment. No full Maven suite,
PostgreSQL/Docker run, frontend/browser checks or paid calls are authorized for
this limited-check pass. Task 1.9 intentionally remains unchecked: API docs are
aligned, but PostgreSQL and frontend recovery integration/acceptance still need
verification. Do not archive A3 or claim end-to-end v2 completion/default enable.
No AGENTS or skill change is needed for these internal boundaries.

## 99. Configurable-platform contract alignment

- [ ] 99.1 Apply the added generic-resource requirement to pending implementation/integration; verify exact VCL/schema/source/receipt contracts and preserve original checked-task evidence and quality gates.
