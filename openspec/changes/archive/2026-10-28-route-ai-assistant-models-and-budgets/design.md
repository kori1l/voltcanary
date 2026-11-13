# Design: Route assistant models with one shared budget

The canonical design, request/response/state formats, trust boundaries and migration
rules are in [the target architecture](../../../../docs/target/ai-assistant-orchestration.md). Keep schemas there authoritative
rather than copying provider-specific variants into this change.

## Specific implementation boundary
Routing is deterministic application policy, not another LLM classification call. Small-model qualification and decomposition precede escalation. Reserve calls, tokens, time and price before network I/O with optimistic concurrency and a parent-plan ceiling. Fail closed for missing authority, ledger writes or strict-price information. Unknown usage remains charged/reserved conservatively. Currency/rate snapshots, cached-input and reasoning subset accounting are explicit. This change supplies the budget; repair-ai-dashboard-planning-failures consumes it and must not add another coordinator ledger.

Size the actual serialized provider request including conversational memory and
output/reasoning reserve. Required questions, goals and resource facts cannot be
discarded to fit an economical model. A2 uses offline provider fixtures and A1
interfaces; it does not require A3/A4 end-to-end orchestration or live qualification
to complete. Unqualified models are not silently added to production AUTO policy.

## Prerequisites and sequencing
define-ai-assistant-skill-contracts. Reuses existing provider registry, credential handling, model adapters and AssistantTraceRecorder.

Use the [program index](../../README.md#ai-assistant-orchestration-program) for the
noncyclic order and delegated legacy criteria. Implement readers/contracts before
new writers; opt-in generation stays disabled until integration and qualification.

## Validation
Use offline schema, domain and compatibility checks first. Any real-model evidence
belongs to an explicitly authorized capped qualification campaign. Record all
actual calls in the existing trace and shared budget. Apply relevant existing
Java/frontend/connector checks, structure and strict OpenSpec validation; do not
run ML/load testing for this architecture unless that surface actually changes.
