# Change: AI composition over configurable platform capabilities

Status: implementation resumed by the user. The first narrow slice adds default-off native table authoring through existing reviewed owner APIs. Broad composition, runtime and model qualification remain open. This is a generic platform capability, not a specialized transit workflow.

## Why

The platform must compose many company-specific scenarios from typed data, relations, observations, geometry and rules. A separate controller/evaluator branch for every business scenario would defeat that goal.

## What Changes

Expose general schema/query/import/relation/map/derivation/rule/test/review/apply skills to the existing assistant. Support bounded specialist research and qualified per-skill model routing without a business-intent explosion.

## Dependencies

Priority amendment 2026-11-11: follow [AI-first](../README.md#ai-first-execution-priority)
and [LangGraph4j qualification/migration](../adopt-langgraph4j-agent-orchestration/proposal.md).
Requirements/consultations over existing capabilities may proceed before full
G1–G4 completion. Exact missing configurable operations still require their
actual owner slice. Graph scheduling consumes the qualified library runtime;
this change retains product semantics and does not implement another scheduler.

G1–G4 owner contracts, G6 for complete shared reviews/dashboard UI; reuse A1–A5/V/R and one A2 ledger.

## Design and Impact

Complete contracts now include VCL types/methods, efficient dense native storage,
customer DB adapters and strict imports; see the linked normative documents in
design.md. G7/UI/acceptance participate in each vertical slice, not only at the end.

[Configurable platform architecture](../../../docs/target/configurable-operations-platform.md) and [authoritative program](../README.md#configurable-operations-program). Reuse current services, variables, operational schedules/facts, dashboards and assistant orchestration. Existing APIs stay compatible; implementation requires owning migrations/contracts and focused tests.

## Out of Scope

No paid/live campaigns, arbitrary executable SQL/scripts, silent source/identity substitution, duplicate assistant/ledger, automatic publication or legal-policy assumptions. Existing failed A5/A6 gates remain open.


## Requirements and retrieval amendment (2026-11-03)

The [AI collaboration/retrieval specification](../../../docs/target/ai-assistant-collaboration-and-retrieval.md) is normative for these pending extensions: typed requirements, bounded consultations, exact metric evidence, optional ACL-aware pgvector retrieval and all-call budgets. This extends existing G7/A5/A6/G8 ownership; runtime and live trials remain stopped. Existing frozen acceptance/checked-task evidence is retained.

## Second implementer audit

Specification revision2 in the [collaboration contract](../../../docs/target/ai-assistant-collaboration-and-retrieval.md#9-уточнения-второго-аудита-и-повышение-точности) clarifies dependency-specific rebase, dual question/requirements CAS, exact absolute deadlines, ID-only private retrieval, requirement coverage, evidence authority and controlled quality ablations. AI01-AI24 are runtime gates; passing documentation checks is not implementation evidence. Existing completed tasks/pinned policies remain unchanged.

## Spring implementation architecture (2026-11-03)

The [Spring implementation contract](../../../docs/target/ai-assistant-spring-implementation.md) and [proposed ADR-014](../../../docs/adr/ADR-014-ai-spring-extension-boundaries.md) define exact bean/port/advisor/AOP, transaction, worker, dependency and observation boundaries. S0-S6 extend existing A5/A6/G7/G8 ownership; SPR01-SPR15 supplement existing semantic/quality gates. No second ledger/memory, hidden SDK loops or automatic implementation/live authorization is introduced. The completed Spring AI migration is preserved.

## Bus expressibility/readiness audit

The [bus readiness contract](../../../docs/target/configurable-operations-bus-readiness.md) adds BUS01-BUS10 capture, occurrence routing, exact door predicate and exit/non-arrival gates to this pending work. It preserves generic core ownership, required-stops-only user policy and all existing frozen fixtures/quality gates; it is not evidence of implementation.
