# ADR-014: Spring extension boundaries for the AI assistant

- Date: 2026-11-03
- Status: Proposed target decision; implementation and live trials remain stopped
- Owners: existing A5/A6/G7/G8 changes

## Context

The assistant already uses Spring AI2.0.2 for provider exchanges and its own
durable plans, budgets, questions, reviews and receipts. Framework interception
can improve reuse, but automatic tool/validation loops, proxy assumptions and
unwired observation registries can bypass or obscure these boundaries.

## Decision

Use Spring AI ChatClient/models/tools/RAG modules and Spring DI, transactions,
executors and Micrometer. Retain one durable application orchestrator/ledger.
Add two non-recursive CallAdvisor guards for immutable prepared exchange and
reserved dispatch. Use one narrowly scoped application observation aspect;
keep authorization, budget, idempotency, semantic compilation and owner effects
explicit. Reuse persisted conversation projections, not another memory store.

Automatic tool and structured-output validation loops remain disabled on the
proposal path. Every external chat/embedding/rerank call needs its own prior
reservation. Manually constructed clients/models receive Boot registries through
DI. Async work needs durable leases and current authorization; @Async/@Scheduled
and thread-local context do not provide durability or transferable permission.

The [Spring implementation contract](../target/ai-assistant-spring-implementation.md)
owns exact package/bean/advisor/aspect/transaction bindings, sequencing and SPR01–SPR15.
The [collaboration contract](../target/ai-assistant-collaboration-and-retrieval.md)
owns product/requirements/delegation/retrieval semantics.

## Consequences

Domain skills reuse one exchange pipeline while retaining typed contracts and
postconditions. Framework integration requires real Spring-context/provider
fixtures and PostgreSQL recovery tests. Additional guards do not qualify model
quality. A future alternative auto-tool/stream/autonomous-auth profile requires
explicit versioned design and qualification, not an unreviewed annotation switch.

No production dependency, stored policy digest or completed migration is changed
by this proposed decision. Existing stronger release gates remain in force.
