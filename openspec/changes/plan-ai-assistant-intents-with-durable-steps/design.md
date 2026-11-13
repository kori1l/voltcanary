# Design: Plan assistant intents with durable dependent steps

2026-11-11: [AI-first](../README.md#ai-first-execution-priority) and
[library migration](../adopt-langgraph4j-agent-orchestration/design.md) supersede
handwritten traversal as a target. Preserve A3 records/contracts and acyclic
user-plan dependencies; bounded library control-flow cycles do not permit
cyclic step dependencies. A3/1.9 is integrated with the graph slice, not a
prerequisite that must first archive this change.

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


The canonical design, request/response/state formats, trust boundaries and migration
rules are in [the target architecture](../../../docs/target/ai-assistant-orchestration.md). Keep schemas there authoritative
rather than copying provider-specific variants into this change.

## Specific implementation boundary
PLAN/CONTINUE/AMEND/ASK/UNSUPPORTED are validated against the current catalogue. The application checks DAG/type invariants and resolves typed step_output/fact_ref bindings. The model selects the intended authorized fact/resource; the server establishes its identity and provenance from receipts, not regex pronouns. Persist accepted input before remote work. Plan and immutable review are distinct. A failed or UNKNOWN dependency blocks downstream use; partial successes remain visible. Updating a plan never erases performed actions or grants future blanket confirmation.

## Conversational continuation

Persist pending question identity, exact question payload and its step/revision together with focus and chronological resource links. Completed plans do not erase conversation resource receipts. Reload is a storage read with no model call; subsequent model requests receive the relevant actual dialogue and authorized results.

## Prerequisites and sequencing
define-ai-assistant-skill-contracts and route-ai-assistant-models-and-budgets. Reuses existing scoped conversation history, task/review revisions and execution receipts.

Use the [program index](../README.md#ai-assistant-orchestration-program) for the
noncyclic order and delegated legacy criteria. Implement readers/contracts before
new writers; opt-in generation stays disabled until integration and qualification.

## Validation
Use offline schema, domain and compatibility checks first. Any real-model evidence
belongs to an explicitly authorized capped qualification campaign. Record all
actual calls in the existing trace and shared budget. Apply relevant existing
Java/frontend/connector checks, structure and strict OpenSpec validation; do not
run ML/load testing for this architecture unless that surface actually changes.

## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
