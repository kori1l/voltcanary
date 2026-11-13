# Design: Reviewed source mappings and reusable solution packages

Target-only. The [shared configurable architecture](../../../docs/target/configurable-operations-platform.md) owns canonical semantics and service boundaries.

## Implementation Boundary

**Normative complete design (2026-11-03):**
G5 packages consume GX connections and G0/G1/G3 mapping/import contracts. Files, API sync and remote mutation remain distinct jobs with explicit consistency, source hashes/keys and per-target outcomes. Packages cannot bypass DDL review, CAS, idempotency, exact source schema fingerprint or credential isolation.

Contracts: [VCL](../../../docs/target/configurable-operations-language.md),
[storage/adapters](../../../docs/target/configurable-operations-storage.md),
[strict imports](../../../docs/target/configurable-operations-imports.md) and
[acceptance A01–A66](../../../docs/target/configurable-operations-acceptance.md).
These decisions supersede tentative storage/interpreter wording below.

Templates bind to generic resource/operator capabilities and install ordinary reviewed resources with receipts. Provider-specific trip/direction/service-day parsing stays in adapters; mandatory-service policy is explicit table data. Packages cannot add permissions, arbitrary network endpoints or code. Updates create migration diffs and preserve pins. Unsupported protocols require a separate vetted adapter, not model-generated production scripts.

## Compatibility and Validation

Preserve canonical resource IDs, signed access, existing arithmetic AST v1, current circle endpoints and saved reviewed plans. New schemas/operators must be versioned and advertised only when implemented. Preview and runtime use the same canonical contracts. Require independent cross-domain, negative, replay and access tests; a model explanation is not evidence of completion. Keep implementation tasks unchecked until code and verification exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.

## Bus expressibility/readiness audit

The [bus readiness contract](../../../docs/target/configurable-operations-bus-readiness.md) adds BUS01-BUS10 capture, occurrence routing, exact door predicate and exit/non-arrival gates to this pending work. It preserves generic core ownership, required-stops-only user policy and all existing frozen fixtures/quality gates; it is not evidence of implementation.
