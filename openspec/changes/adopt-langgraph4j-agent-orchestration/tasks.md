# Implementation and verification

Resumed on 2026-11-13. Eight tasks are complete and 2.4 and 3.2–3.4 remain open.
See the [current checkpoint](design.md#atomic-requirements-and-answer-binding--2026-11-13).

Core integration tasks1.1–1.4/2.1–2.3 and ordinary-API Docker lifecycle3.1 are implemented and verified.
Specialists, remaining UI/owner acceptance, in-place migration and final whole-change
validation remain pending; see design.md for source-versioned evidence.

Task2.4 now has internal typed requirements, question arbitration, plan-fenced
JSON/history storage, bounded consultation policy and a native-subgraph worker
using the original A2 ledger. The JDBC boundary now commits a requirements snapshot
and journal with the root revision, question and affected pending review revocation.
The internal answer adapter binds CHOICE/TEXT/VALUES to the claimed input and the
question and requirements revisions. Production role/loader registration, durable executor,
requirements/consultation API/UI and their end-to-end acceptance are still pending;
the checkbox remains open.

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
- [ ] 2.4 Integrate G7 requirements and consultation semantics through library subgraphs, bounded depth/fan-out/concurrency, one root question arbiter and existing shared reservations; avoid a parallel custom traversal/scheduler.

## 3. Supported workflow, cutover and evidence

- [x] 3.1 Run the existing-device/dashboard clarification -> review -> confirm -> exact saved readback -> same-ID edit workflow through the actual graph and ordinary APIs; verify restart, partial failure and receipt reuse with deterministic fixtures. Do not infer real-model quality from fixture success.
- [ ] 3.2 Complete the applicable A3/A5/V/R/G7 integration checks and frontend recovery/trace behavior for the declared supported scope; retain all unmet original gates and unsupported owner capabilities explicitly.
- [ ] 3.3 Pin engine/graph versions per plan, implement reviewed migration/rollback, preserve legacy reads/saved execution and retire replaced generator traversal after parity. No silent engine fallback or default enable.
- [ ] 3.4 Run affected backend/frontend checks plus structure, strict OpenSpec and AI contract checks; update current docs only for implemented behavior. Hand separately authorized real-model/portability/cost qualification to A6 without rescoring historical failures.
