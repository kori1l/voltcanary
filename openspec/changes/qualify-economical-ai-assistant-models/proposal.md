# Change: Qualify economical assistant models and rollout

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Use the [current implementation order](../README.md#ai-first-execution-priority) for configurable scope. Live evaluation follows the owning change’s authorization and qualification gates.


Current status (2026-11-14): A6 is the last change in the release queue. Begin
qualification after the other release changes deliver their functional scope and
G8 platform acceptance is complete. Include their assistant scenarios in the
release corpus and reuse independent outcome evidence from their owners. A6
supplies shared final model and rollout evidence for the remaining delegated
criteria. Keep first-pass, recovered and final results separate and require
explicit bounded live authorization.

## Why
A valid schema or cheap token price does not prove that an entire conversation succeeds economically. Provider portability and bounded small-model quality need independent outcomes and full-task cost evidence.

## What Changes
- Define a frozen cross-skill/paraphrase corpus and provider-neutral replay with deterministic independent outcome checks.
- Qualify economical profiles and a different-provider candidate against the same quality and full-task economic gates.
- Reuse trace/browser/execution evidence for C3, stage5/6 and v2 rollout; keep failures and rollback readable.
- Separate immutable first-pass model quality from recovery effectiveness: no automatic correction/model judges in the first-pass profile, no rescoring a repaired candidate as an initial success, and all required calls included in cost.

## Dependencies

2026-11-11: qualification for the new engine consumes
[LangGraph4j integration evidence](../archive/2026-11-13-adopt-langgraph4j-agent-orchestration/design.md)
under [AI-first priority](../README.md#ai-first-execution-priority). Prepare
corpora/profiles early; final qualification retains A5/V/R requirements below.
Graph fixtures and partial supported-scope evidence do not close full A6 or make
library adoption depend on A6 completion. Record engine/graph/checkpoint versions
with every measured profile; no old result qualifies a new runtime automatically.
The scoped GPT-5 nano role-v6 continuation is available as diagnostic evidence.
It verified exact typed-name binding and same-task continuation in the STANDARD
profile across five successful model exchanges. Keep the prior failed run in
the evidence and use the A6 corpus for portability, quality and cost claims.

A6 also consumes A5 domain skill migration and the implemented offline verify
and repair integration. It supplies their outstanding live acceptance and
reuses archived baseline evidence without rescoring it.

Authoritative order and delegation: [orchestration program](../README.md#ai-assistant-orchestration-program).

## Design and impact
See [target architecture](../../../docs/target/ai-assistant-orchestration.md) and its canonical JSON schemas/examples.
Start with offline contract/adversarial tests and declare all limits, rates, reference profile, quality gates and allowed test resources before any real calls. Compare complete economical pipelines including planner/context/repair/failures with a fixed priced stronger-reference configuration on identical tasks; do not benchmark one output token price or drop failed runs from the denominator. Existing dashboard critical trials and quality thresholds remain. At least two actual provider implementations are required for a verified portability claim; missing access yields incomplete qualification rather than invented evidence. A stopped/paused live campaign does not automatically restart. New policy/skill/prompt versions require relevant requalification, not retrospective rescoring.

Changes affect ai-agent-service application/domain/adapters, existing conversation/provider settings/UI boundaries where relevant, and their contracts. Owning domain services retain authority. Existing history/traces/receipts are reused.

## Out of scope
No unrequested implementation or paid trials from this proposal, no fine-tuning,
semantic phrase-matching fallback, direct model mutations, fabricated telemetry,
provider credential changes, automatic publication or hidden model escalation.
A6 measures the library selected by adopt-langgraph4j-agent-orchestration;
framework implementation belongs to that change, not a second A6 engine.

## Acceptance
The requirements in specs/assistant-model-qualification/spec.md and every task below require implementation and evidence. Preserve existing failed-quality records, default-off rollout and immutable legacy execution. No checkbox is completed merely by creating a proposal.

## Requirements and retrieval amendment (2026-11-03)

The [AI collaboration/retrieval specification](../../../docs/target/ai-assistant-collaboration-and-retrieval.md) is normative for these pending extensions: typed requirements, bounded consultations, exact metric evidence, optional ACL-aware pgvector retrieval and all-call budgets. Implementation follows the [AI-first order](../README.md#ai-first-execution-priority). Live evaluation follows the owning change’s authorization and evidence gates. Existing frozen acceptance/checked-task evidence is retained.

## Second implementer audit

Specification revision2 in the [collaboration contract](../../../docs/target/ai-assistant-collaboration-and-retrieval.md#9-уточнения-второго-аудита-и-повышение-точности) clarifies dependency-specific rebase, dual question/requirements CAS, exact absolute deadlines, ID-only private retrieval, requirement coverage, evidence authority and controlled quality ablations. AI01-AI24 are runtime gates; passing documentation checks is not implementation evidence. Existing completed tasks/pinned policies remain unchanged.

## Spring implementation architecture (2026-11-03)

The [Spring implementation contract](../../../docs/target/ai-assistant-spring-implementation.md) and [proposed ADR-014](../../../docs/adr/ADR-014-ai-spring-extension-boundaries.md) define exact bean/port/advisor/AOP, transaction, worker, dependency and observation boundaries. S0-S6 extend existing A5/A6/G7/G8 ownership; SPR01-SPR15 supplement existing semantic/quality gates. No second ledger/memory, hidden SDK loops or automatic implementation/live authorization is introduced. The completed Spring AI migration is preserved.
