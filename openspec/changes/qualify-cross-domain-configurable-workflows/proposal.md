# Change: Cross-domain qualification of configurable workflows

Status: proposed 2026-11-03 after the user corrected the product scope. Planning only; runtime implementation remains stopped until explicitly resumed. This is a generic platform capability, not a specialized transit workflow.

## Why

The platform must compose many company-specific scenarios from typed data, relations, observations, geometry and rules. A separate controller/evaluator branch for every business scenario would defeat that goal.

## What Changes

Prove flexibility by composing transit visits, fleet work duration and a third held-out domain using the same operators/APIs. Grade actual state/evidence, schema changes and user outcomes rather than valid JSON or model prose.

## Dependencies

G8 platform acceptance consumes G1–G7 and precedes final A6 model qualification.
Keep platform outcome fixtures and evidence reusable by A6 so that the final
campaign grades the same released workflows.

## Design and Impact

Complete contracts now include VCL types/methods, efficient dense native storage,
customer DB adapters and strict imports; see the linked normative documents in
design.md. G7/UI/acceptance participate in each vertical slice, not only at the end.

[Configurable platform architecture](../../../docs/target/configurable-operations-platform.md) and [authoritative program](../README.md#configurable-operations-program). Reuse current services, variables, operational schedules/facts, dashboards and assistant orchestration. Existing APIs stay compatible; implementation requires owning migrations/contracts and focused tests.

## Out of Scope

No paid/live campaigns, arbitrary executable SQL/scripts, silent source/identity substitution, duplicate assistant/ledger, automatic publication or legal-policy assumptions. Existing failed A5/A6 gates remain open.


## Requirements and retrieval amendment (2026-11-03)

The [AI collaboration/retrieval specification](../../../docs/target/ai-assistant-collaboration-and-retrieval.md) is normative for these pending extensions: typed requirements, bounded consultations, exact metric evidence, optional ACL-aware pgvector retrieval and all-call budgets. Implementation follows the [AI-first order](../README.md#ai-first-execution-priority). Live evaluation follows the owning change’s authorization and evidence gates. Existing frozen acceptance/checked-task evidence is retained.

## Second implementer audit

Specification revision2 in the [collaboration contract](../../../docs/target/ai-assistant-collaboration-and-retrieval.md#9-уточнения-второго-аудита-и-повышение-точности) clarifies dependency-specific rebase, dual question/requirements CAS, exact absolute deadlines, ID-only private retrieval, requirement coverage, evidence authority and controlled quality ablations. AI01-AI24 are runtime gates; passing documentation checks is not implementation evidence. Existing completed tasks/pinned policies remain unchanged.

## Spring implementation architecture (2026-11-03)

The [Spring implementation contract](../../../docs/target/ai-assistant-spring-implementation.md) and [proposed ADR-014](../../../docs/adr/ADR-014-ai-spring-extension-boundaries.md) define exact bean/port/advisor/AOP, transaction, worker, dependency and observation boundaries. S0-S6 extend existing A5/A6/G7/G8 ownership; SPR01-SPR15 supplement existing semantic/quality gates. No second ledger/memory, hidden SDK loops or automatic implementation/live authorization is introduced. The completed Spring AI migration is preserved.

## Bus expressibility/readiness audit

The [bus readiness contract](../../../docs/target/configurable-operations-bus-readiness.md) adds BUS01-BUS10 capture, occurrence routing, exact door predicate and exit/non-arrival gates to this pending work. It preserves generic core ownership, required-stops-only user policy and all existing frozen fixtures/quality gates; it is not evidence of implementation.
