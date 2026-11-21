# Design: AI composition over configurable platform capabilities

The [shared configurable architecture](../../../docs/target/configurable-operations-platform.md) owns canonical semantics and service boundaries. The broad design remains pending; the narrow native-table slice below is implemented separately.

## Native table authoring slice

Execution amendment 2026-11-11: [AI-first](../README.md#ai-first-execution-priority)
and [LangGraph4j adoption](../archive/2026-11-13-adopt-langgraph4j-agent-orchestration/design.md)
own traversal/checkpoint mechanics. G7 owns requirements/consultation semantics
and uses that one graph; Spring executors/leases are infrastructure, not another
graph scheduler. Existing-capability agent slices need only their real owner
dependencies, not completion of all G1–G4. Optional RAG remains later.

`CREATE_NATIVE_TABLE` is registered in the existing primary skill/model pipeline,
gated by `app.ai.orchestration.native-data-enabled=false` and the signed owner's
current language profile. Disabled packages do not alter existing pinned policy
digests. The model proposes symbolic fields using the seven manual-form scalar
types; the server reserves real dataset/field/review/receipt IDs and generates the
same COLLECTION document as the manual form. The common-vcl authoring fixture is
shared by Java and TypeScript parity tests. No generic SQL tool is added.

Preparation checks the exact reserved IDs, owner diff, semantic digest and expiry,
then saves the existing immutable AI review with a TTL no longer than the owner
review. The shared schema preview requires explicit confirmation. Execution uses
the exact saved owner review/version/digest/key and checks the receipt plus actual
schema readback before publishing a dataset output. Lost responses retain UNKNOWN
and reconcile the same review. After AI review expiry, UNKNOWN recovery may only
GET the original receipt/resource; it cannot POST apply/reconcile or renew consent.
There is no new budget ledger, SDK callback loop or autonomous worker.

Controlled HTTP, draft/parity, preparation and PostgreSQL review-store tests
exercise this boundary. They do not qualify live model interpretation, compound
workflows, row/import/reference/map/RULE authoring or cross-service runtime outcomes.

## Implementation Boundary

**Normative complete design (2026-11-03):**
G7 participates in every vertical slice. It consumes the same generated VCL method registry, compiler, connection capability descriptors and ValidationManifest as UI. It never receives DB secrets or a generic SQL tool. Worker/model roles do not widen access. Compound changes bind actual resource IDs from receipts; material changes require renewed consent. A2 remains sole budget ledger.

Contracts: [VCL](../../../docs/target/configurable-operations-language.md),
[storage/adapters](../../../docs/target/configurable-operations-storage.md),
[strict imports](../../../docs/target/configurable-operations-imports.md) and
[acceptance A01–A66](../../../docs/target/configurable-operations-acceptance.md).
These decisions supersede tentative storage/interpreter wording below.

Progressively disclose authorized schemas/field units/relations/operators/sample evidence. Planner selects generic operations and source-linked goals; specialist questions return through the coordinator. Workers cannot recursively grant tools or mutate directly. All provider calls use existing reservations and PINNED/AUTO semantics. Review may use local refs to resources not yet created; bind them only through successful receipts. Preserve partial results, replay-before-model-resolution and material-change consent guards.

## Compatibility and Validation

Preserve canonical resource IDs, signed access, existing arithmetic AST v1, current circle endpoints and saved reviewed plans. New schemas/operators must be versioned and advertised only when implemented. Preview and runtime use the same canonical contracts. Require independent cross-domain, negative, replay and access tests; a model explanation is not evidence of completion. Keep implementation tasks unchecked until code and verification exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.

## Requirements and retrieval amendment (2026-11-03)

The [AI collaboration/retrieval specification](../../../docs/target/ai-assistant-collaboration-and-retrieval.md) is normative for these pending extensions: typed requirements, bounded consultations, exact metric evidence, optional ACL-aware pgvector retrieval and all-call budgets. This extends existing G7/A5/A6/G8 ownership; runtime and live trials remain stopped. Existing frozen acceptance/checked-task evidence is retained.

## Second implementer audit

Specification revision2 in the [collaboration contract](../../../docs/target/ai-assistant-collaboration-and-retrieval.md#9-уточнения-второго-аудита-и-повышение-точности) clarifies dependency-specific rebase, dual question/requirements CAS, exact absolute deadlines, ID-only private retrieval, requirement coverage, evidence authority and controlled quality ablations. AI01-AI24 are runtime gates; passing documentation checks is not implementation evidence. Existing completed tasks/pinned policies remain unchanged.

## Spring implementation architecture (2026-11-03)

The [Spring implementation contract](../../../docs/target/ai-assistant-spring-implementation.md) and [proposed ADR-014](../../../docs/adr/ADR-014-ai-spring-extension-boundaries.md) define exact bean/port/advisor/AOP, transaction, worker, dependency and observation boundaries. S0-S6 extend existing A5/A6/G7/G8 ownership; SPR01-SPR15 supplement existing semantic/quality gates. No second ledger/memory, hidden SDK loops or automatic implementation/live authorization is introduced. The completed Spring AI migration is preserved.

## Bus expressibility/readiness audit

The [bus readiness contract](../../../docs/target/configurable-operations-bus-readiness.md) adds BUS01-BUS10 capture, occurrence routing, exact door predicate and exit/non-arrival gates to this pending work. It preserves generic core ownership, required-stops-only user policy and all existing frozen fixtures/quality gates; it is not evidence of implementation.
