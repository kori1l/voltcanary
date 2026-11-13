# Change: Migrate assistant workflows to reviewed domain skills

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


Current status (2026-10-28): A5 is the current implementation owner (4/15 acceptance tasks checked; core diagnostic/context/output stabilization verified offline; remaining workflows pending). The server create-to-refinement bypass is fixed; general design and opt-in assessment are implemented, but campaign04 quality failed and calibration remains open. Execute core skills/context/strict output/workflows, then first-pass measurement without correction/judges; integrate V/R last. Local v2 is enabled, final quality failed.

## Why
Contracts must replace the actual competing routing/prompt/repair paths while preserving ordinary device, integration and dashboard behavior. A second assistant engine would leave the reported failures and cost multiplication intact.

## What Changes
- Migrate existing supported workflows through the shared plan/skill/context/model boundaries and retain legacy read/execution compatibility.
- Implement reviewed dependent device/source/dashboard steps and exact targeted widget operations through existing owning services.
- Integrate the existing outcome verifier and typed recovery seam, remove semantic regex from the v2 path, and preserve feature parity.
- Stabilize compact versioned skills, constrained output, context and core workflow parity; record no-repair first-pass quality before adding independent outcome checks and resilience at the end of A5.

## Dependencies

2026-11-11 priority: [LangGraph4j assessment/migration](../adopt-langgraph4j-agent-orchestration/proposal.md)
precedes the former unconditional A5 -> A6 queue. A5 owns skills/parity and V/R,
using that one graph; it does not own a competing scheduler. Applicable offline
integration predicates may be checked incrementally without declaring all A5
acceptance complete. Its original first-pass-before-final-V/R evidence gate is
retained; graph fixture success cannot replace real-model evidence.
Build on implemented A1/A2/A4 and A3 runtime, retaining A3 task1.9 as integration acceptance within A5. Improve skills/context/output/core workflows and measure first-pass quality first, with automatic repair and model judges disabled in the diagnostic profile. Add V outcome assessment and R resilience at the end of A5; they are not prerequisites to core development or extra program stages. A6 supplies final acceptance and separate first-pass/recovered metrics. Do not reopen completed A1/A2/A4 tasks merely to redesign prompts. C3 requirements are absorbed here and qualified afterward.

Authoritative order and delegation: [orchestration program](../README.md#ai-assistant-orchestration-program).

## Design and impact
See [target architecture](../../../docs/target/ai-assistant-orchestration.md) and its canonical JSON schemas/examples.
First implement a complete ordinary-device/HSL/edit vertical slice behind an explicit v2 writer flag. Expose a source-connection intent only for a connector with an actual authorized execution/receipt contract; fixed HSL does not imply arbitrary MQTT support. Existing stored composite HSL reviews keep their original semantics. New separate steps need ordinary domain idempotency and receipt reconciliation. Moving/resizing/settings use the existing semantic compiler and server packing, not model coordinates/full definitions. The existing verify and repair changes remain the owners of postconditions and recovery UI; no duplicate checker, retry ledger or renderer is introduced.

Changes affect ai-agent-service application/domain/adapters, existing conversation/provider settings/UI boundaries where relevant, and their contracts. Owning domain services retain authority. Existing history/traces/receipts are reused.

## Out of scope
No unrequested implementation or paid trials from this proposal, no fine-tuning,
semantic phrase-matching fallback, direct model mutations, fabricated telemetry,
provider credential changes, automatic publication or hidden model escalation.
Library adoption is explicitly scoped to adopt-langgraph4j-agent-orchestration;
A5 consumes its runtime rather than implementing a second framework.

## Acceptance
The requirements in specs/assistant-domain-skills/spec.md and every task below require implementation and evidence. Preserve existing failed-quality records, default-off rollout and immutable legacy execution. No checkbox is completed merely by creating a proposal.

## Requirements and retrieval amendment (2026-11-03)

The [AI collaboration/retrieval specification](../../../docs/target/ai-assistant-collaboration-and-retrieval.md) is normative for these pending extensions: typed requirements, bounded consultations, exact metric evidence, optional ACL-aware pgvector retrieval and all-call budgets. This extends existing G7/A5/A6/G8 ownership; runtime and live trials remain stopped. Existing frozen acceptance/checked-task evidence is retained.

## Second implementer audit

Specification revision2 in the [collaboration contract](../../../docs/target/ai-assistant-collaboration-and-retrieval.md#9-уточнения-второго-аудита-и-повышение-точности) clarifies dependency-specific rebase, dual question/requirements CAS, exact absolute deadlines, ID-only private retrieval, requirement coverage, evidence authority and controlled quality ablations. AI01-AI24 are runtime gates; passing documentation checks is not implementation evidence. Existing completed tasks/pinned policies remain unchanged.

## Spring implementation architecture (2026-11-03)

The [Spring implementation contract](../../../docs/target/ai-assistant-spring-implementation.md) and [proposed ADR-014](../../../docs/adr/ADR-014-ai-spring-extension-boundaries.md) define exact bean/port/advisor/AOP, transaction, worker, dependency and observation boundaries. S0-S6 extend existing A5/A6/G7/G8 ownership; SPR01-SPR15 supplement existing semantic/quality gates. No second ledger/memory, hidden SDK loops or automatic implementation/live authorization is introduced. The completed Spring AI migration is preserved.
