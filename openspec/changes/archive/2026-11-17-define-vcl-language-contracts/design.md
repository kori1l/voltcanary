# Design: Define and implement the VCL language contract and compiler front end

Normative contracts are docs/target/configurable-operations-language.md and its JSON Schema/examples. Implement one strict parser, canonicalizer, type/unit registry and compiler front end; registered scalar/graph method signatures are versioned. Schema-valid output still requires reference/access/arity/cycle/budget checks. No CEL/SQL/script alternate execution path. Old numeric AST v1 remains unchanged. Publish exact methods/capabilities to UI and AI from the same registry. Cross-service wire DTOs contain contracts, not duplicate interpreter ownership.

## Binding Decisions

The language/storage/import documents linked in proposal.md are normative and supersede earlier tentative layout/interpreter wording. Target contracts are complete design decisions, not evidence of implemented behavior. Operator/storage/adapter changes require a versioned reviewed amendment and independent acceptance. Existing identities, signed grants, old AST/review compatibility and one assistant ledger remain.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.

## First implementation boundary

common-vcl packages the existing canonical grammar directly as a Maven resource;
VclRegistry derives method arities/graph config projections from that source and
publishes signature metadata. VclCompiler accepts an owner-supplied immutable
VclMetadata port and returns STATIC_TYPED_IR with defensive config/document copies,
compiler/grammar/unit/scope/dependency digests. It does not authorize activation,
execute SQL/files or implement temporal state. JCS is provided by the Apache2.0
java-json-canonicalization1.1 library; exact rational unit factors avoid early
rounding. No new deployable service or Spring bean is introduced.

Current verification and required remaining boundaries:
[implementation document](../../../docs/current/architecture/vcl-compiler-front-end.md).

## Scalar implementation boundary

VclScalarCompiler reuses the parser's closed Expression/Literal grammar projections
and VclExpressionTypes, producing immutable VclScalarProgram nodes in one checked
pass. Runtime kernels and explicit ScalarValue outcomes live in analytics-service,
with pinned decimal policy and registry/unit/scope fences. Owner-supplied input
snapshots remain a port; no new endpoint/bean activates rules. All21 scalar methods
are implemented under the versioned circle/WGS84 profile. Registry fingerprints
include the kernel contract. Spatial context separates immutable geometry from
fresh authorization and fails closed on unavailable pins. Existing numeric AST v1
is retained; polygons and temporal state are not part of this scalar checkpoint.

## Owner bundle infrastructure

CompilationBundlePreparation coordinates static compilation, trusted owner
manifest validation and fresh reauthorization before insert/inspection. The
manifest pins dependencies, grants/policies/spatial resources, input generations,
operator versions, scope, key/snapshot digests, start boundary and owner cost
estimates. CompilationBundle seals canonical OWNER_VALIDATED_IR bytes and digest.
V034/JdbcCompilationBundleStore provide account-scoped insert-only persistence,
same-byte replay, conflicting-ID rejection and integrity/version checks. H2 and
real Docker PostgreSQL exercise concurrent identical/conflicting inserts and
restarts. This is infrastructure through ports: real configurable owner metadata,
capability/index/cost/readiness adapters and HTTP/UI/AI wiring remain task1.4/1.5.
There is no rule activation worker or fresh-latest recompilation on restore.

## Native metadata transport increment

NativeDatasetSnapshots in analytics consumes the ordinary signed device owner
description API, with no account/user authority from a document. Its immutable
NativeDatasetSnapshot implements VclMetadata for explicitly captured native
datasets, preserves actual readiness and re-reads original pins at compiler scope
fences and inspection. Strict bounded JSON, canonical COLLECTION hash checks,
redirect rejection and owner timeouts precede compiler use. Scope/head/schema/
capability drift fails explicitly. No binding/reference/grant authority is inferred.
The short-lived snapshot expires after20 seconds without extending its lifetime.
This advances task1.4; RULE Owners composition, binding/grant/source validation,
bundle endpoint wiring and UI/AI parity still remain open.

## Source and grant owner adapter increments

SourceBindingSnapshots consumes signed source descriptions from the immutable
source-owner journal, checks actor/account/exact revision and refreshes its pins.
The conservative legacy source profile cannot qualify capture or negative evidence.
AutomationGrantSnapshots consumes the real alert-owner reviewed grant API and
pins identity, exact field/revision membership and epoch. Scope/lifetime/epoch
drift or expanded effect/delivery claims fail closed. Each permission/pin use
refreshes the owner; this compile snapshot is not an autonomous effect permit.
Both adapters are implemented prerequisites. EvidencePolicySnapshots now reads the
actual reviewed alert-owned policy, rejects expanded disclosure/limits/identity
claims and checks its exact grant epoch/scope/expiry against the real grant
snapshot. Each resource pin renews both owners; the closed common-vcl profile
preserves immutable private-reference budgets. Capture-readiness owners, complete
qualified bundle composition and public bundle/UI/AI invocation remain pending; metadata does not
implement evidence retention execution or effect admission.

## Static owner composition increment

RuleCompilationSnapshots derives source/native/grant/policy targets from one
parsed RULE and composes the existing signed owner adapters. The document-bound
snapshot invokes the shared compiler, with exact actor/account/rule checks,
common owner scope and immutable grant/policy pins. It refreshes all owner fences
on use and requires exact grant membership for every exposed source/native schema
field. This first profile rejects partial schema grants and unavailable literal
resource references. Bounded field membership refreshes the grant once per schema.
It returns STATIC_TYPED_IR only, without manufacturing capture/operator readiness,
as-of projection, cost estimates or OWNER_VALIDATED_IR. Public compilation/bundle
and evaluator integration remain task1.4 prerequisites.

## Actual accepted-observation read increment

SourceCaptureSnapshot reads bounded accepted-observation pages through the signed
source owner, using its original schema/generation/head cut. Each read renews
actual owner authority before and after remote I/O; progress cannot retarget the
snapshot to a newer head. Strict shared codecs check exact field membership and
conservative quality flags. Telemetry verifies contiguous checkpoints, stored
canonical-payload checksums and the actual journal generation. Legacy checksum-less
facts are not repaired or silently filtered. This is an actual data-read prerequisite
for1.4, without qualified upstream coverage, clock/hold policy, operator/cost
readiness, public bundle admission or activation.

## Journal-backed static RULE compilation

The explicit journal-backed profile replaces legacy source schema descriptions
with signed captured-head descriptions for every referenced binding. It applies
an internal total input-count budget to the pinned checkpoint spans and includes
the exact cuts in the static compiler's scope digest. The same native, grant and
policy checks apply. The existing metadata-only compilation profile retains its
scope identity. Journal-backed compilation advances task 1.4 while qualified
coverage, operator readiness, evaluator cost and bundle admission remain open.

For a single direct journal `window_aggregate`, batch preparation checks the
actual versioned reducer and window assignment plan. Its membership bound uses
the original checkpoint span and the kernel's maximum window fan-out. Unsupported
upstream pipelines fail explicitly. This bound covers pure batch input work.
State size and end-to-end runtime cost remain separate requirements.
The prepared batch can page its original cut after checking all pinned owners.
It repeats those checks after remote I/O before returning the page.

Bounded retained input reads traverse the complete original local checkpoint
range. They validate cross-page event uniqueness and the total encoded size
before returning any pages as a batch. The input digest binds the cut and the
canonical records in checkpoint order. This retains the distinction between a
complete local prefix and source coverage, which remains UNKNOWN.
The retained input budget is capped at 8 MiB of canonical page bytes.

The window preview projects validated journal fields into the shared reducer
for a single subject and a window from the pinned assignment plan. An ambiguous
subject or a quarantined selected timestamp produces UNKNOWN. The preview
reports source coverage and clock quality separately from its local result and
keeps finalization false.

Bundle preparation reauthorizes the stored manifest after the store returns.
Revocation during storage blocks disclosure while preserving the insert-only
row for authorized reconciliation.

The source readiness check uses the same document-bound compilation and field
grants as static previews. It rejects metadata-only sources and the current
journal profile. Bundle restoration separately verifies the pinned manifest
with the sealing checks and exact canonical replay. The saved graph must
match the canonical RULE's node declarations and input links, with dependencies
preceding their consumers and the terminal last. The schema dependency pins
must match the binding and dataset targets and revisions declared by the graph.
