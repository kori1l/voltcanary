# Change: Adopt library-backed Java agent orchestration

Status: implementation in progress, explicitly requested on 2026-11-11.
LangGraph4j core1.9.9 is integrated for new assistant turns, context continuation
and confirmation under the existing default-off generation gate. On2026-11-12,
the ordinary-API Docker lifecycle passes13 deterministic checks with actual
owner create/update/readback and two AI-service restarts. Specialist semantics,
UI/live-model qualification remains open. The user excluded migration of historical
test conversations and plans. New conversations still require durable history and
restart recovery.
No live model campaign or provider-setting change is part of this implementation.

## Why

The assistant already has Spring AI exchanges, typed skills, durable plans,
reviews, receipts and budgets. Continuing the configurable storage/temporal
platform first delays a working agent, while extending handwritten scheduling
duplicates capabilities available in a graph orchestration library.

The user selected Java and a LangGraph4j compatibility assessment. LangGraph4j
is an independent Java project inspired by LangGraph; it is not the official
Python/TypeScript LangGraph runtime. No graph database is requested.

## What Changes

- Make AI-first delivery the active default, ahead of broad G0/G1/G3/G4 work.
- Assess and pin a released LangGraph4j core version against Java 21, Boot 4
  and the repository's Spring AI 2.0.2; do not assume compatibility from marketing.
- Replace application-owned graph traversal/scheduling with library nodes,
  edges and resumable execution, retaining application-owned domain contracts.
- Integrate graph persistence with the existing PostgreSQL plan/ledger owners;
  retain one authoritative state per concern and no competing execution engine.
- Prove an existing-capability workflow before expanding configurable data,
  temporal rules, optional RAG or role-based model selection.

## Dependencies and Ownership

Reuse the completed Spring AI migration and A1/A2/A4 baseline. This change owns
library qualification, graph execution, checkpoint integration and cutover.
A5 owns domain workflow parity and V/R; A3 retains durable-plan acceptance;
G7 owns requirements and specialist semantics; A6 owns real-model qualification.
Overlapping scheduler work in G7/100.2 and G7/102.2 consumes this graph engine,
not a second custom scheduler. Existing unfinished criteria remain open.

Full G0/G1/G3/G4 completion is not a prerequisite for graph work over existing
APIs. A concrete unsupported capability remains blocked on its real owner and
is excluded from the first supported workflow rather than mocked as implemented.

## Impact and Non-goals

Affected future surfaces: ai-agent-service orchestration/configuration,
PostgreSQL adapters, focused frontend recovery/trace integration and tests.
No Python agent service, Neo4j, second model transport, second budget ledger,
arbitrary SQL tools or default-on rollout. Existing data and reviewed execution
remain compatible. Planning does not authorize paid trials or provider changes.

See [execution priority](../README.md#ai-first-execution-priority) and the
[framework contract amendment](../../../docs/target/ai-assistant-spring-implementation.md#langgraph4j-assessment-and-migration-2026-11-11).
