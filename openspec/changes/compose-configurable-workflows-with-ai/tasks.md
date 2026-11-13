# Implementation and Verification

Priority 2026-11-11: [AI-first](../README.md#ai-first-execution-priority).
Tasks100.1–100.2/101.* and applicable102.* join the library-backed agent slice
after its compatibility gate;100.3 knowledge/RAG remains later. Scheduler work
in100.2/102.2 uses [the LangGraph4j change](../adopt-langgraph4j-agent-orchestration/tasks.md),
not a second custom execution engine. Broad1.1–1.6/3.1 still require real owners.
This reorder checks no implementation task and preserves all existing evidence.

## 4. First native authoring slice

- [x] 4.1 Add default-off CREATE_NATIVE_TABLE through the existing registry/model pipeline; reserve actual owner IDs, share manual schema semantics/preview, bound review expiry, require explicit consent and exact receipt/schema readback, and keep expired UNKNOWN recovery GET-only. Verify controlled owner HTTP faults, preparation fences, shared seven-type parity, PostgreSQL lease/claim races and frontend review/recovery behavior. This does not complete broad tasks1.1–1.6/3.1 or live model qualification.

Checkpoint2026-11-04: the AI service regression passes307 tests, including actual
PostgreSQL review expiry/inspection races; dependency reactor tests also pass.
The changed frontend scope passes15 tests, typecheck and production build.
The first full frontend run passes428/429 (one ML timing failure; its23-case file
passes separately); a second full run encounters multiple timing failures and is
stopped. There is no green full frontend baseline or browser visual qualification
for this slice. OpenSpec104/104, structure, VCL and AI contract checks pass.
Ignored evidence: artifacts/g7-native-*. No live/paid model campaign was run.

- [ ] 2.1 Add language/adapter/import adversarial and remote UNKNOWN recovery scenarios to early AI slices; verify no SQL/code execution or authority amplification.

- [ ] 1.1 Define generic AI skill contracts/capability discovery and schema/relationship progressive loaders.
- [ ] 1.2 Implement typed goal interpretation/questions for subjects, work definitions, geometry and policy sources.
- [ ] 1.3 Implement schema/record/ref/map/derivation/rule proposals and shared dry-run/review/apply receipts.
- [ ] 1.4 Integrate bounded research jobs and A2-backed qualified per-skill profiles without hidden escalation.
- [ ] 1.5 Test novel schemas, compound local refs, user facts, reload/replay, partial failure and cross-account denial.
- [ ] 1.6 Reuse V/R acceptance and shared renderer; keep model quality gates open until independent qualification.


- [ ] 3.1 Implement owner reservations, DRAFT_ONLY previews, exact receipt resolution and A64/A66 without a second ledger.

## 100. Requirements, consultation and retrieval integration

- [ ] 100.1 Implement typed requirement slots, source-message bindings, conflict/stale propagation and one root question arbiter; verify AI01/AI02/AI04.
- [ ] 100.2 Implement consultation registry/DTOs and bounded nested consultation semantics through the single qualified LangGraph4j runtime, replay/cancel/revision fences and shared CHAT/EMBED/RERANK reservations; verify AI03/AI05/AI06. Graph/checkpoint mechanics are owned by adopt-langgraph4j-agent-orchestration/2.4, not a second scheduler.
- [ ] 100.3 Implement opt-in versioned knowledge ingestion, hybrid retrieval, ACL-before-hydration/egress, owner metric resolution and AI07-AI14; retain simple no-RAG/no-delegation paths.

## 101. Second audit: accuracy and unambiguous execution

- [ ] 101.1 Implement dependency-specific read manifests, continuation/cancellation fences, REBASE_ACCEPTED provenance, exact question/requirements CAS and existing absolute deadline semantics; verify AI15-AI17/AI23.
- [ ] 101.2 Implement requirementCoverage and policy/evidence applicability, ID-only private retrieval, provenance deduplication and independently checked persisted outcomes; verify AI18-AI22/AI24.

## 102. Spring and Spring AI implementation bindings

- [ ] 102.1 Integrate requirements/consultation pure kernels through focused application beans and existing registry/ports; reuse the shared Spring exchange pipeline for devices/dashboard/VCL/rule/research skills.
- [ ] 102.2 Implement bounded named executor, due-work scanner, DB-clock lease/heartbeat/fencing, after-commit wakeup plus durable recovery, explicit async trace context and current-turn authorization around the single library graph runtime; verify SPR07-SPR09/SPR14. Reuse graph/checkpoint work from adopt-langgraph4j-agent-orchestration/2.3; infrastructure dispatch must not duplicate graph traversal.
- [ ] 102.3 Implement optional knowledge adapters using Spring AI embedding/RAG modules under shared reservations and private ID-only hydration; conditional disabled wiring and scope-aware cache must pass SPR11-SPR13.
- [ ] 102.4 Expose capabilities honestly: post-session autonomous execution remains unavailable until an explicit identity-owned delegation contract is designed, implemented and qualified; preserve read/cancel/receipt access.

## 103. Bus scenario expressibility and evidence

- [ ] 103.1 Use BUS01-BUS10 as a semantic gate: preserve confirmed mandatory-only scope, review exact witness predicate, distinguish exit/non-arrival, and show capability gaps rather than an unverified working workflow.
