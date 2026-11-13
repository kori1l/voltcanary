# Design: Manual table, map, rule and evidence workbench

The [shared configurable architecture](../../../docs/target/configurable-operations-platform.md)
owns canonical semantics and service boundaries. Implementation begins with the
published native collection/row/LIVE owner profile; unsupported owners stay gated.

## Implementation Boundary

**Normative complete design (2026-11-03):**
Every delivery milestone includes manual UI and AI-compatible review; G6 is a module owner, not a prerequisite to postpone all AI until the end. Table queries avoid mandatory reference expansion. Show business-facing source/quality/approval controls; keep physical storage keys and AST details outside normal flows. Original saved receipts remain independent of later edits.

Contracts: [VCL](../../../docs/target/configurable-operations-language.md),
[storage/adapters](../../../docs/target/configurable-operations-storage.md),
[strict imports](../../../docs/target/configurable-operations-imports.md) and
[acceptance A01–A66](../../../docs/target/configurable-operations-acceptance.md).
These decisions supersede tentative storage/interpreter wording below.

Use schema/capability-driven feature modules and API clients; no business-specific giant route pages or hidden AI-only mutation path. UI labels describe business concepts while IDs/AST stay internal. Rule builder and optional expression editor compile to the same canonical graph. Preview uses actual authorized samples and explicit empty/stale/unknown states; schema edits show dependency impact. Browser rendering never drives evaluation.

## Compatibility and Validation

Preserve canonical resource IDs, signed access, existing arithmetic AST v1, current circle endpoints and saved reviewed plans. New schemas/operators must be versioned and advertised only when implemented. Preview and runtime use the same canonical contracts. Require independent cross-domain, negative, replay and access tests; a model explanation is not evidence of completion. Keep implementation tasks unchecked until code and verification exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
