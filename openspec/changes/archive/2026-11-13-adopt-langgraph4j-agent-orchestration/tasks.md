# Implementation and verification

Completed and verified on 2026-11-13.
See the [current checkpoint](design.md#atomic-requirements-and-answer-binding--2026-11-13).

## Pre-release test data

Use a fresh test conversation for acceptance. If obsolete test conversations
or plans interfere, delete only the identified test records in the local test
database and rerun with a fresh conversation. Keep the conversation history mechanism
and verify recovery with records created by the current graph. Migration and
backfill of historical test conversations and plans are outside this change.
Preserve evaluation evidence and domain resources created by confirmed actions.

Core integration tasks1.1–1.4/2.1–2.3 and ordinary-API Docker lifecycle3.1 are implemented and verified.
The specialist branches and current-graph restart checks also passed.
The supported-scope owner and browser checks passed.
See design.md for source-versioned evidence.

Task2.4 now has internal typed requirements, question arbitration, plan-fenced
JSON/history storage, bounded consultation policy and a native-subgraph worker
using the original A2 ledger. The JDBC boundary now commits a requirements snapshot
and journal with the root revision, question and affected pending review revocation.
The internal answer adapter binds CHOICE/TEXT/VALUES to the claimed input and the
question and requirements revisions. Authenticated requirements inspect and answer
routes are wired for the text slot profile. Consultation inspection and
revision-bound cancellation are also wired. Device and dashboard name
questions without choices are published with the graph checkpoint, and the
typed answer binds the name to the waiting step. The registered name specialist
uses the same root budget and completes in the deterministic Docker workflow.
The frontend reads consultation status for the active plan. The registered
dashboard name workflow passed ordinary-API checks for root clarification,
same-task continuation, loader results and delegation with parent continuation.
Broader G7 worker/owner qualification remains with its original change.

Execution order:1.* ->2.1–2.3 ->3.1 (basic workflow) ->2.4 (specialists) ->3.2–3.4.
Task3.2 consumes applicable owner predicates, not completion/archive of whole
A3/A5/V/R/G7 changes. Optional RAG and full configurable-platform acceptance are
not graph-adoption prerequisites. Preserve A5's first-pass-before-final-V/R gate.

## 1. Compatibility and ownership gate

- [x] 1.1 Inventory existing traversal, stores, leases, API states and A3/A5/G7/V/R overlaps; record exactly what library code replaces and the single owner for each persisted concern.
- [x] 1.2 Run a dependency-isolated LangGraph4j spike against Java 21 / Boot 4 / Spring AI 2.0.2; pin a released core artifact and source tag, inspect transitive dependencies/license, and verify nodes, conditional edges, pause/resume and bounded subgraphs with deterministic fixtures. Reject implicit platform downgrade or hidden model/tool retries.
- [x] 1.3 Prove PostgreSQL checkpoint/plan consistency, CAS/fencing and restart around commit boundaries; select an atomic adapter or reconciled projection design with one dispatch authority and preserve UNKNOWN outcomes.
- [x] 1.4 Publish the measured adoption decision and exact replacement/compatibility matrix; if the gate fails, record the blocker before selecting another library rather than silently continuing custom orchestration.

## 2. Library-backed runtime integration

- [x] 2.1 Implement actual LangGraph4j nodes/edges for planning, scoped context, skills, validation, review and persisted question/confirmation waits; preserve public contracts and replace the corresponding handwritten traversal.
- [x] 2.2 Connect every model node to the existing reserved single-exchange Spring AI port; preserve one ledger, usage/uncertainty, PINNED selection, deadlines and replay before provider I/O. Keep Spring target guards owned by A5/102.*.
- [x] 2.3 Implement versioned checkpoint persistence/reconciliation and bounded execution through focused adapters; verify concurrent resumes, cancellation, stale revisions, authorization loss and crashes before/after model or owner I/O without duplicate effects.
- [x] 2.4 Integrate G7 requirements and consultation semantics through library subgraphs, bounded depth/fan-out/concurrency, one root question arbiter and existing shared reservations; avoid a parallel custom traversal/scheduler.

## 3. Supported workflow, cutover and evidence

- [x] 3.1 Run the existing-device/dashboard clarification -> review -> confirm -> exact saved readback -> same-ID edit workflow through the actual graph and ordinary APIs; verify restart, partial failure and receipt reuse with deterministic fixtures. Do not infer real-model quality from fixture success.
- [x] 3.2 Complete the applicable A3/A5/V/R/G7 integration checks and frontend recovery/trace behavior for the declared supported scope; retain all unmet original gates and unsupported owner capabilities explicitly.
- [x] 3.3 Pin engine/graph versions per new plan, verify durable history and same-plan recovery across restart, and retire replaced generator traversal after parity. Keep generation opt-in and reject engine mismatches. Historical pre-release conversations and plans do not require migration.
- [x] 3.4 Run affected backend/frontend checks plus structure, strict OpenSpec and AI contract checks; update current docs only for implemented behavior. Hand separately authorized real-model/portability/cost qualification to A6 without rescoring historical failures.
