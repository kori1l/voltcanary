# Change: Define and implement the VCL language contract and compiler front end

Status: implementation in progress, explicitly resumed 2026-11-03. First G0/M0 library increment verified; no service activation or paid/live model trial is implied.

## Why

A configurable platform requires explicit executable contracts, efficient optional storage features and safe access to customer-owned data, rather than per-scenario code or implicit import/write behavior.

## What Changes

Normative contracts are docs/target/configurable-operations-language.md and its JSON Schema/examples. Implement one strict parser, canonicalizer, type/unit registry and compiler front end; registered scalar/graph method signatures are versioned. Schema-valid output still requires reference/access/arity/cycle/budget checks. No CEL/SQL/script alternate execution path. Old numeric AST v1 remains unchanged. Publish exact methods/capabilities to UI and AI from the same registry. Cross-service wire DTOs contain contracts, not duplicate interpreter ownership.

## Dependencies

First configurable-program stage (G0), with no self-dependency; consumes existing resource/identity contracts. Owner validation and execution kernels remain in their existing services.

## Authoritative Contracts

[Language](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [imports](../../../docs/target/configurable-operations-imports.md), [acceptance A01–A66](../../../docs/target/configurable-operations-acceptance.md). See the [program](../README.md#configurable-operations-program).


## Implemented checkpoint

[Current front end](../../../docs/current/architecture/vcl-compiler-front-end.md):
parser, closed registry/schema projections, typed static compilation and canonical
digests in common-vcl;63 compiler tests pass. Non-spatial scalar execution now
uses immutable checked IR and pure analytics-service kernels, including WGS84
distance and circle membership. Sealed owner-manifest/bundle persistence is tested
on H2 and PostgreSQL. Real owner/UI/AI integration remains unfinished.
Added pure Java RFC8785 dependency io.github.erdtman:
java-json-canonicalization:1.1; existing Jackson2/networknt versions are retained.
Geometry additionally pins MIT-licensed net.sf.geographiclib:GeographicLib-Java:2.1.
