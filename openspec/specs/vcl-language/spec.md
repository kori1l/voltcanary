# vcl-language Specification

## Purpose
TBD - created by archiving change define-vcl-language-contracts. Update Purpose after archive.
## Requirements
### Requirement: Explicit complete VCL contracts

VCL SHALL define exact data types, methods, input/output ports, argument signatures, canonicalization, versions, null/unknown/error semantics and bounded graph execution through one closed registry.

#### Scenario: Unknown method
- **WHEN** a document names an unregistered operator, argument or conversion
- **THEN** strict validation rejects it before provider/domain effects

#### Scenario: Numeric fidelity
- **WHEN** a decimal, duration or INT64 crosses UI/API/SQL boundaries
- **THEN** its declared precision/unit/type is preserved without implicit coercion

### Requirement: Compiler validation beyond grammar

Compilation SHALL validate method arity, generic input types, field/resource scope, units, graph cycles, indexes/projections and execution cost independently of model claims and structural JSON Schema.

#### Scenario: Schema-valid wrong type
- **WHEN** a duration is compared to unconverted decimal hours
- **THEN** compilation fails with a typed unit/type diagnostic

#### Scenario: Replay identity
- **WHEN** an accepted definition is replayed after schema/operator changes
- **THEN** it resolves its pinned bundle/version or fails explicitly rather than using latest

### Requirement: Revision two closed grammar and semantic contract

The VCL compiler SHALL enforce explicit NULL, REF target discriminators, declared collection keys, chained import conversions, discriminated preconditions, one terminal alert output and root timing coherence.

#### Scenario: Revision two implementer boundary
- **WHEN** a grammar-valid document uses a nullable key, a cross-session witness or a mismatched root lateness
- **THEN** semantic compilation rejects it before owner effects; grammar conformance alone is insufficient

