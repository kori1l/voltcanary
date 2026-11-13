# ADR-015: Library-backed execution of the assistant graph

- Date: 2026-11-11
- Status: Accepted for core integration; specialist orchestration and in-place migration pending
- Owner: adopt-langgraph4j-agent-orchestration

## Context

The Java assistant already owns scoped conversation/plan state, A2 budgets,
typed skills, review confirmation and owner receipts. Its recursive coordinator
and context-continuation loops duplicate graph execution functionality. The
user selected Java and LangGraph4j while prioritizing the agent over broader
configurable-data/temporal work.

## Decision

Use released `langgraph4j-core:1.9.9` through an application execution port.
The adapter owns actual nodes, conditional edges and a bounded traversal.
Spring AI2.0.2 remains the only model SDK path; no stock agent executor or
additional provider integration module is imported.

Persist primitive-only library checkpoints inside the authoritative plan JSON
using the same existing transaction/revision/lease fence. New plans pin engine
and graph definition with persisted schema2; legacy plans remain schema1.
Questions and confirmations are durable user-turn boundaries. Explicit requests
re-enter from canonical state; checkpoints never grant dispatch authority or
automatic replay of uncertain inference/owner effects.

Sequential execution stays on the request thread to preserve current trace
context. Bounded in-turn context graphs keep attempt authority in A2 and cannot
resume independently. Persisted specialist subgraph namespaces and async work
require their remaining G7 contracts and tests before exposure.

## Consequences

Domain validation, authorization, budgets, review/version/digest confirmation
and receipt reconciliation remain explicit. They are not delegated to library
convenience loops. Existing acyclic step dependencies remain separate from
bounded graph control-flow cycles. Library exception wrappers are decoded back
to original application errors without retrying the node.

No second checkpoint database, budget ledger or in-memory conversation store is
introduced. New generation on an incompatible/legacy active plan cannot silently
switch engines. Legacy history and saved reviews remain usable; new generation
uses an explicitly new plan until reviewed in-place migration is implemented.
Rollback must retain a schema2-compatible reader/receipt service.

See [current implementation](../current/architecture/spring-ai-assistant.md#langgraph4j-core-integration--2026-11-11),
[change design and evidence](../../openspec/changes/adopt-langgraph4j-agent-orchestration/design.md)
and [Spring extension requirements](../target/ai-assistant-spring-implementation.md).
Core integration does not qualify A5/A6 model quality or complete all specialist,
dashboard lifecycle, migration or rollout requirements.
