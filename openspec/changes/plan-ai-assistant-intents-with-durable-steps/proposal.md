# Change: Plan assistant intents with durable dependent steps

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


Current status (2026-10-28): A3 runtime implemented and offline-verified (8/9). Task1.9 integration acceptance remains open and is completed within A5; local v2 is enabled explicitly. Do not claim A3 complete from partial reload evidence.

## Why
A message may create a device, connect its source and request a dashboard. Phrase matching cannot reliably select that workflow, retain dependencies or resolve a later reference across restart.

## What Changes
- Replace semantic routing with model decisions over compact supported-intent summaries and durable state.
- Persist typed plans, dependent steps, retained goals, facts and receipt references; validate plans before scheduling.
- Persist pending question payload/identity and resource focus/chronology; retain scoped created-resource links across completed plans and reload without model calls.
- Implement continuation, amendment, clarification and controls without repeating completed mutations or bypassing review confirmation.

## Dependencies

2026-11-11: [AI-first](../README.md#ai-first-execution-priority) and the
[LangGraph4j change](../adopt-langgraph4j-agent-orchestration/proposal.md) own new
graph traversal/checkpoint implementation. A3 preserves acyclic typed plan
dependencies, records and task1.9 acceptance; it does not build another engine
or require full G0–G4 completion. Historical checked runtime remains evidence
for its original snapshot, not proof of library cutover.
define-ai-assistant-skill-contracts and route-ai-assistant-models-and-budgets. Reuses existing scoped conversation history, task/review revisions and execution receipts.

Authoritative order and delegation: [orchestration program](../README.md#ai-assistant-orchestration-program).

## Design and impact
See [target architecture](../../../docs/target/ai-assistant-orchestration.md) and its canonical JSON schemas/examples.
PLAN/CONTINUE/AMEND/ASK/UNSUPPORTED are validated against the current catalogue. The application checks DAG/type invariants and resolves typed step_output/fact_ref bindings. The model selects the intended authorized fact/resource; the server establishes its identity and provenance from receipts, not regex pronouns. Persist accepted input before remote work. Plan and immutable review are distinct. A failed or UNKNOWN dependency blocks downstream use; partial successes remain visible. Updating a plan never erases performed actions or grants future blanket confirmation.

Changes affect ai-agent-service application/domain/adapters, existing conversation/provider settings/UI boundaries where relevant, and their contracts. Owning domain services retain authority. Existing history/traces/receipts are reused.

## Out of scope
No unrequested implementation or paid trials from this proposal, no fine-tuning,
semantic phrase-matching fallback, direct model mutations, fabricated telemetry,
provider credential changes, automatic publication or hidden model escalation.
Library adoption is explicitly scoped to adopt-langgraph4j-agent-orchestration;
it is not a separate framework implementation inside A3.

## Acceptance
The requirements in specs/assistant-intent-plans/spec.md and every task below require implementation and evidence. Preserve existing failed-quality records, default-off rollout and immutable legacy execution. No checkbox is completed merely by creating a proposal.
