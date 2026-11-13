# Design: Load bounded assistant skill context on demand

The canonical design, request/response/state formats, trust boundaries and migration
rules are in [the target architecture](../../../../docs/target/ai-assistant-orchestration.md). Keep schemas there authoritative
rather than copying provider-specific variants into this change.

## Specific implementation boundary
Server-owned skill instructions and untrusted resource/user data are distinct. A model sees only required facts, current choices, base review, pending goals and availability. Each snapshot identifies asOf/version/completeness. NEED_CONTEXT can invoke only registered typed loaders with scoped opaque cursors, never arbitrary URLs/SQL. Another model continuation consumes the same budget. Mandatory instructions/goals cannot disappear under truncation. Do not call a summarizer by default or persist hidden reasoning. New context is reauthorized, and temporary refs never grant access.

## Conversational continuation

The context includes the current raw message and actual prior dialogue, pending question/choices, chronological resource events and focus. Preserve the mandatory conversational kernel, disclose omissions, and expose only scoped registered conversation.history paging for skill NEED_CONTEXT. Planner uses its existing decisions (including ASK), not an undeclared NEED_CONTEXT outcome. Dialogue interpretation belongs to the model; server selection uses state links and ordering rather than text matching.

## Prerequisites and sequencing
define-ai-assistant-skill-contracts, route-ai-assistant-models-and-budgets and plan-ai-assistant-intents-with-durable-steps.

Use the [program index](../../README.md#ai-assistant-orchestration-program) for the
noncyclic order and delegated legacy criteria. Implement readers/contracts before
new writers; opt-in generation stays disabled until integration and qualification.

## Validation
Use offline schema, domain and compatibility checks first. Any real-model evidence
belongs to an explicitly authorized capped qualification campaign. Record all
actual calls in the existing trace and shared budget. Apply relevant existing
Java/frontend/connector checks, structure and strict OpenSpec validation; do not
run ML/load testing for this architecture unless that surface actually changes.

## Implemented boundary (2026-10-26)

`orchestration/context/BoundedSkillRequests` supplies the A3 request port;
`ContextLoading` validates registered scoped loaders and durable V009 cursors.
`JdbcContextHistory` projects the existing transcript, `ScopedReviewContext`
reads exact immutable reviewed actions, and `ScopedDomainContextLoaders` uses
ordinary device/source/dashboard APIs. Parameter rules and schemas are explicit
step contract data; loader payloads remain separate untrusted data envelopes.
The mandatory source-message/question/fact/resource kernel is never truncated;
only neighbouring dialogue can be omitted with counts/cursors. Requests beyond
mandatory capacity fail or require narrowing. A2 budgets all model continuations.

The factories/read adapters are wired independently of generation. A5 still
supplies production skill packages, provider role serialization, domain compilers
and UI integration. Legacy semantic extractors remain exclusively on the legacy
path until its parity migration. This change adds no new semantic extractor and
never sends a v2 failure into legacy interpretation. See current API documentation
for exact limits, error behavior and the two-scenario verification command.
