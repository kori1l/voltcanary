# Design: Qualify economical assistant models and rollout

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


The canonical design, request/response/state formats, trust boundaries and migration
rules are in [the target architecture](../../../docs/target/ai-assistant-orchestration.md). Keep schemas there authoritative
rather than copying provider-specific variants into this change.

## Specific implementation boundary
Start with offline contract/adversarial tests and declare all limits, rates, reference profile, quality gates and allowed test resources before any real calls. Compare complete economical pipelines including planner/context/repair/failures with a fixed priced stronger-reference configuration on identical tasks; do not benchmark one output token price or drop failed runs from the denominator. Existing dashboard critical trials and quality thresholds remain. At least two actual provider implementations are required for a verified portability claim; missing access yields incomplete qualification rather than invented evidence. A stopped/paused live campaign does not automatically restart. New policy/skill/prompt versions require relevant requalification, not retrospective rescoring.

## Prerequisites and sequencing

### First-pass quality versus resilience — amendment 2026-10-28

A5 measures first-pass quality before adding V/R at its end; A6 later qualifies
both the core and completed pipeline on versioned configurations. A5 and A6 are
the remaining historical domain/quality stages; the 2026-11-11
[AI-first/library migration](../README.md#ai-first-execution-priority) now precedes
them. Record library/engine/graph versions in qualification snapshots.
Corpus/metric design may be prepared
early, but final A6 acceptance requires completed A5/V/R integration.

Predeclare two profiles and first-pass/final quality gates before calls:
1. Diagnostic first-pass: no automatic format/preparation/outcome correction and
   no model judge. One initial candidate per logical stage; necessary planning,
   step and context calls are counted, not disguised as a single-call pipeline.
2. Recovery-enabled: explicit bounded correction/assessment, same original task
   denominator, immutable first-candidate outcomes and linked later attempts.

Use independent code-based expected effects to grade both after generation.
Do not count a correct rejection by a verifier as successful task completion.
If nuance needs human/model grading, predeclare and calibrate it separately;
identify evaluator-only usage versus runtime usage while reporting total cost.
Offline replay proves mechanics, not live model quality. Record first-pass
complete-task success, recovered successes among initial failures, final success,
unreached steps, false acceptance/rejection, calls, tokens, latency and priced/
unknown costs. Recovered success never changes the first-pass numerator; a retry
is not a fresh trial. Keep originally failed scores unchanged.

Compare one-component changes on fixed prompts/tasks/model snapshots and a
separate held-out paraphrase set. Prompt examples must not contain held-out
answers. Preserve frozen dashboard gates, including existing repetitions and
preservation/access requirements; declare any additional first-pass threshold
explicitly rather than silently applying the final recovery threshold to it.
Cost covers failures, context, repairs and judges; cheap successful output alone
does not establish savings. Stop at the declared campaign ceiling. No broad live
campaign or extra evaluator/model switch is authorized by this document.
migrate-ai-assistant-workflows-to-domain-skills and implemented offline verify/repair integration. This change supplies their outstanding live acceptance, so full V/R archival is not a prerequisite. Reuses archived baseline evidence without rescoring it.

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

## Requirements and retrieval amendment (2026-11-03)

The [AI collaboration/retrieval specification](../../../docs/target/ai-assistant-collaboration-and-retrieval.md) is normative for these pending extensions: typed requirements, bounded consultations, exact metric evidence, optional ACL-aware pgvector retrieval and all-call budgets. This extends existing G7/A5/A6/G8 ownership; runtime and live trials remain stopped. Existing frozen acceptance/checked-task evidence is retained.

## Second implementer audit

Specification revision2 in the [collaboration contract](../../../docs/target/ai-assistant-collaboration-and-retrieval.md#9-уточнения-второго-аудита-и-повышение-точности) clarifies dependency-specific rebase, dual question/requirements CAS, exact absolute deadlines, ID-only private retrieval, requirement coverage, evidence authority and controlled quality ablations. AI01-AI24 are runtime gates; passing documentation checks is not implementation evidence. Existing completed tasks/pinned policies remain unchanged.

## Spring implementation architecture (2026-11-03)

The [Spring implementation contract](../../../docs/target/ai-assistant-spring-implementation.md) and [proposed ADR-014](../../../docs/adr/ADR-014-ai-spring-extension-boundaries.md) define exact bean/port/advisor/AOP, transaction, worker, dependency and observation boundaries. S0-S6 extend existing A5/A6/G7/G8 ownership; SPR01-SPR15 supplement existing semantic/quality gates. No second ledger/memory, hidden SDK loops or automatic implementation/live authorization is introduced. The completed Spring AI migration is preserved.
