# Design: Reusable map features and table-linked map selection

Target-only. The [shared configurable architecture](../../../docs/target/configurable-operations-platform.md) owns canonical semantics and service boundaries.

## Implementation Boundary

**Normative complete design (2026-11-03):**
POINT/circle and bounded polygon are declared geometry capabilities. Polygon rings must be closed, bounded and non-self-intersecting; holes cannot intersect outer/other rings. Publish exact vertex/ring/local-extent limits and geographic-kernel error bounds. Unsupported extents/accuracy return typed unavailable rather than approximate success. inside/distance and zone_session use one versioned geometry kernel. No UI-only zone evaluation.

Contracts: [VCL](../../../docs/target/configurable-operations-language.md),
[storage/adapters](../../../docs/target/configurable-operations-storage.md),
[strict imports](../../../docs/target/configurable-operations-imports.md) and
[acceptance A01–A66](../../../docs/target/configurable-operations-acceptance.md).
These decisions supersede tentative storage/interpreter wording below.

device-service spatial module owns feature revisions; server geometry and existing map/preview renderer share the same declared capability. Store WGS84 GeoJSON coordinate order explicitly, validate shapes/limits, show actual metres/overlap/uncertainty and resolve nearby alternatives through user choice. AI invokes the same prepare/save APIs and cannot submit guessed coordinates as verified source data.

The circle owner slice stores immutable account-scoped WGS84 revisions in
V032. The authenticated revision command uses an expected head, and retries
of the same geometry and revision return the original result. Read APIs
return exact revision and digest for user and scoped machine callers.
The slice supports circles only, with at most 1024 features per account
and 128 revisions per feature. Other shapes, review/import and map
authoring remain open.

## Compatibility and Validation

Preserve canonical resource IDs, signed access, existing arithmetic AST v1, current circle endpoints and saved reviewed plans. New schemas/operators must be versioned and advertised only when implemented. Preview and runtime use the same canonical contracts. Require independent cross-domain, negative, replay and access tests; a model explanation is not evidence of completion. Keep implementation tasks unchecked until code and verification exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
