# Implementation and Verification

Completion checkpoint 2026-11-17. All 25 tasks are verified for the compiler,
implemented owner profiles and shared registry integration. Across the complete
backend reactor and the successful continuation after three test fixture
repairs, current Surefire reports contain 1933 tests, zero failures, errors or
skips. The repairs update the mock dispatch default method, capability-gated
catalogue expectations and the mandatory widget in a creation scenario.
Production assistant behavior was unchanged by those fixture repairs.
Frontend typecheck/build, native contract tests, repository structure and strict
OpenSpec pass. Docker source coverage tests cover sustained, window aggregate,
circle zone session and ASOF enrichment through real owner services. Shared
UI/AI native table authoring has ordinary API and deterministic assistant evidence.
G6 and G7 retain generic graph editing and AI rule authoring, while G8/A6
retain their cross-domain and model release qualification.

## 1. Compiler and integration

- [x] 1.1 Implement the normative parser/type/method/operator registry, canonical digests and generated capability/schema projections.
- [x] 1.2 Validate every target grammar example and add negative arity/type/unit/access/cycle/limit cases.
- [x] 1.3 Implement pure scalar kernels and compiler front-end interfaces without changing numeric AST v1.
- [x] 1.4 Integrate versioned compilation manifests with owner-domain validation and immutable bundle storage.
- [x] 1.5 Prove UI/AI use identical canonical contracts and malformed/unknown fields never reach execution.

The 2026-11-14 increment for task 1.4 requires the owner ASOF index readiness
check to match the exact dataset schema revision pinned by the compiled node.
The 28 focused manifest validation and H2 bundle storage tests pass. Task 1.4
remains open for owner runtime and cost checks and public bundle preparation.
Reviewed cutover preview and apply now match the start boundary and source
input generations to the sealed bundle. The focused analytics reactor passed
58 bundle, H2 storage, and owner snapshot tests.

For task 1.5, native table preparation in ai-agent-service now parses the
collection with common-vcl before calling the owner. A regression case checks
that an unknown collection field causes zero owner HTTP requests during both
preparation and execution. The 25 focused native owner and workflow tests pass.

The reviewed-table Compose smoke verifies assistant creation and exact schema
readback, receipt replay, manual owner review of the equivalent VCL contract,
and HTTP 400 for an unknown field in the manual request. The deterministic
provider used 24 fixture calls. The native workbench frontend suite passed 11
tests, including the shared seven-type authoring fixture. These runs cover the
native table path. RULE profile-specific end-to-end checks remain open.

The public RULE compilation HTTP integration test persists and restores
bundles for `sustained`, direct `window_aggregate`, direct
`interval_union_window`, `window_session` and `zone_session`. It verifies the pinned
`window_session` execution profile and rejects requests beyond the
retained-input budget before creating a bundle. It uses HTTP owner fixtures
and H2. The source coverage Compose smoke separately exercises the
`sustained` and direct `window_aggregate` paths against real owners.
The compilation service also resolves a `lookup_asof` manifest over
the owner HTTP contract, stores the enriched window bundle, and verifies
the exact reference manifest digest after H2 readback.
For `zone_session`, the HTTP owner fixture supplies the circle, the persisted
bundle contains its exact digest, and preview rejects changed geometry even
when the owner returns the same revision number.
The Compose source-coverage smoke also verifies `zone_session` through the
gateway with a real device-owned circle revision and a qualified telemetry
proof. The persisted bundle returns the same checkpoint during preview.
This smoke covers preparation through preview.
On 2026-11-17 the Compose smoke also provisions a native reference table,
inserts reviewed rows, seals an ASOF snapshot and compiles an enriched
window aggregate through the gateway. Its preview returns the same checkpoint
and reference manifest digest. The run finishes successfully and restores
telemetry capture configuration. Evidence is retained in the ignored
artifacts/g0-lookup-resumed-smoke.log.

Identical verified compilations now derive the bundle identity from canonical
sealed bytes and reuse the insert-only stored bundle. The HTTP integration test
repeats compilation and verifies identical receipts.

Task 1.5 closure uses the shared registry published by device-service, the
frontend/native authoring fixture covering all seven supported creation types,
and the reviewed-table Compose smoke through the actual assistant and manual
owner APIs. Unknown fields fail before effects in the owner and before HTTP
in the assistant. The common parser/compiler negative vectors cover the
remaining VCL document kinds. Generic rule graph editing and AI rule authoring
retain their implementation ownership under G6/1.3 and G7/1.3.
On 2026-11-17, frontend typecheck and production build, repository structure and
105 strict OpenSpec validations pass.

## 2. Scalar execution increments

- [x] 2.1 Implement and verify immutable checked scalar IR and all19 non-spatial kernels in analytics-service, including full Kleene tables, null/error/evidence, checked arithmetic, pinned rounding and scope/version fences.
- [x] 2.2 Implement sealed owner-manifest/bundle infrastructure, account-scoped insert-only JDBC storage, replay/integrity/version guards and real H2/PostgreSQL concurrency tests.
- [x] 2.3 Implement and verify the signed native dataset HTTP metadata adapter, immutable compiler snapshots, fresh scope/head/capability fences and closed owner response parsing.
- [x] 2.4 Integrate source-owned schema descriptions through shared signed HTTP transport, strict wire decoding and fresh actor/account/revision/readiness fences without granting temporal execution from legacy metadata.
- [x] 2.5 Integrate alert-owned reviewed grant inspection through strict signed HTTP decoding, exact field/revision scopes and fresh epoch/revocation checks; permission pins must not imply capture or effect-boundary readiness.
- [x] 2.6 Integrate actual reviewed evidence-policy owner pins and fresh grant-bound policy inspection, with strict decoding and no inferred evidence retention/runtime readiness.
- [x] 2.7 Integrate actual source capture generation/readiness owner descriptions, preserving explicit cutover and UNKNOWN coverage without deriving input generation from schema identity.
- [x] 2.8 Compose authenticated source/native/grant/evidence-policy snapshots for document-bound static RULE compilation, with exact scope/field membership, fresh owner fences and rejection of unsupported temporal projections; retain separate runtime/bundle readiness gates.
- [x] 2.9 Verify static owner composition against ordinary signed Compose APIs with real native provisioning/row CAS/query, reviewed grants/policies, unsupported ASOF rejection and actual owner revocation, without model calls or RULE activation.
- [x] 2.10 Read bounded actual observation pages through the signed source owner under the original capture generation/head and fresh scope/schema fences, preserving exact field membership and conservative quality without minting temporal authority.
- [x] 2.11 Compose signed journal cuts with the existing native, grant and policy owners for static RULE compilation. Pin input generations and checkpoint spans, enforce a total retained input-count budget, and verify cut identity and fresh authority.
- [x] 2.12 Prepare a pure window-aggregate batch from a direct signed journal cut, with a checked reducer plan, pinned fan-out, bounded membership work and fresh owner checks around each page read.
- [x] 2.13 Materialize the original retained window input within a total encoded-byte budget. Verify exact checkpoint counts and cross-page event uniqueness, bind record contents with a pagination-independent digest and discard incomplete or revoked reads.
- [x] 2.14 Evaluate a single pinned subject/window with the shared reducer over the signed retained input. Preserve UNKNOWN for ambiguous subject or future-skew evidence and report unqualified source quality and open finalization.
- [x] 2.15 Reauthorize the persisted bundle after insertion and verify that revocation during storage prevents disclosure while keeping the original immutable row available for authorized replay.
- [x] 2.16 Check pinned source runtime readiness after shared static compilation and fresh exact-grant validation. Reject the metadata-only and retained-journal profiles with distinct diagnostics.
- [x] 2.17 Reapply sealing checks to the persisted typed IR and owner manifest on restore, requiring exact canonical replay and rejecting invalid manifests even with recomputed payload hashes.
- [x] 2.18 Bind the stored graph structure, operator configurations and schema dependencies to the canonical RULE at seal and restore. Reject changed config, rewired edges, invalid order, missing or duplicate nodes and mismatched schema targets even with recomputed payload hashes.
- [x] 2.19 Enforce an 8 MiB ceiling on retained window preview budgets before journal I/O. Verify the exact ceiling and rejection of invalid budgets.

Graph restore checkpoint 2026-11-05. The full analytics reactor passed 673
tests (582 analytics and 91 shared), including immutable bundle contracts on
H2 and Docker PostgreSQL. Regression cases cover changes to both copies of a
dependency pin and changes to graph configuration or topology. These checks
verify the stored graph independently of owner runtime readiness.

Readiness and restore checkpoint 2026-11-05. The 66 focused owner and bundle
cases cover source denial, grant revocation, H2/PostgreSQL replay and nested
manifest corruption. Qualified positive source progress and runtime admission
remain open under G0/1.4.

Window preview and storage checkpoint 2026-11-05. The 138 focused window,
timing and owner cases pass. Bundle validation exercises revocation before
and during storage, denied inspection and exact authorized replay.

Retained window input checkpoint 2026-11-05. The analytics reactor passed 576
tests with PostgreSQL, followed by 117 focused cases with the content digest and
33 final owner-adapter cases including repaging. A complete local prefix retains
UNKNOWN upstream coverage and unqualified clocks. G0/1.4 still requires qualified
source progress and durable operator readiness.

Journal window checkpoint 2026-11-05. All 115 focused cases pass, including
cut stability, exact budget boundaries, rejection of unsupported upstream
operators and revocation during page I/O. Coverage, state cost and durable
operator readiness remain open under task 1.4.

Journal compilation checkpoint 2026-11-05. The 28 focused owner adapter tests
pass, including original-cut stability, generation change, checkpoint regression,
exact field grants, revocation, repeated bindings and budget overflow. The
journal profile returns `STATIC_TYPED_IR`. Task 1.4 still requires qualified
coverage, operator readiness, evaluator cost and bundle admission.

Observation-read checkpoint2026-11-05: the actual telemetry prefix reader,
freshly signed source route and analytics captured-head adapter pass the full
1697-test backend reactor across16 modules, with no failures/errors/skips
(artifacts/g3-owner-read-backend-tests-final.log). Focused evidence includes11
shared page contracts,46 H2/PostgreSQL source-owner cases,62 H2/PostgreSQL capture/
read/migration cases, one real Kafka/Timescale pipeline case and10 analytics HTTP
adapter cases. V020 preserves legacy payload/generation with null integrity pins;
reads/replay never backfill them. Exact schema/field/checkpoint/generation fences,
byte paging, revocation races and corrupt/missing facts are verified. Structure,
VCL and OpenSpec checks pass. This supplies actual retained data, not qualified
upstream coverage/clock/hold, operator/cost admission, public bundles or UI/AI
qualification;1.4/1.5 remain open.

Task1.3 is verified for all21 scalar methods under the versioned WGS84/circle
profile. Task2.2 is the storage/application-port increment of1.4; real configurable
owner adapters and public prepare/compile integration are still required by1.4.

## 3. Revision-two conformance

- [x] 3.1 Implement revision-2 grammar/types/graph checks and port the documentation vectors into independent backend compiler tests.

## G0/M0 implementation checkpoint — 2026-11-03

common-vcl is registered in the Maven reactor. Tasks1.1/1.2/3.1 are verified by
63 Java tests, including all11 positive and33 negative contract documents and
independent access/precision/encoding/immutability/limits/unit/key checks.
The canonical grammar is packaged from the single existing source; registry
capabilities/schema projections and static typed IR are pure Java, without Spring,
JDBC, Kafka or HTTP. RFC8785 uses java-json-canonicalization1.1 rather than a
custom serializer. External source TEXT identity preserves exact normalization.

VclScalarCompiler shares the strict grammar/type checker and builds immutable
VclScalarProgram IR. analytics-service owns the pure21-method runtime, without
Spring activation or owner I/O. ScalarValue separates known null, bounded unknown
evidence and errors; ScalarDecimalPolicy pins checked precision/scale/rounding.
The shared VclScalarContracts profile participates in registryDigest. WGS84 uses
GeographicLib-Java2.1 with explicit nanometre quantization and a conservative1um
kernel error. Circle snapshots pin geometry/revisions separately from fresh
authorization; unknown accuracy/ambiguous membership never becomes false or zero.

CompilationBundlePreparation checks trusted owner attestations and reauthorizes
before storage or private inspection. V034/JDBC seal versioned canonical bytes;
same identity cannot overwrite a prior bundle, account scopes are isolated and
corruption/version mismatches fail closed. Preparation replay returns stored pins
without consulting latest metadata. No new route/bean activates rules.

Verification: full backend `mvn test` passes1047 tests across all16 reactor modules,
including PostgreSQL/Kafka integration and numeric AST compatibility. Final focused
verification passes159 cases:63 compiler,43 scalar,24 spatial and29 owner/store
cases, including Docker PostgreSQL. `structure:check`, `vcl:check` and OpenSpec
strict validation pass. Logs remain ignored under artifacts.

Next eligible work is1.4: actual configurable-owner metadata, index/capability/
cost/readiness checks, fresh grant/source validation and ordinary prepare/compile
wiring. Test owner ports are synthetic, not cross-service evidence. Where needed,
implement the prerequisite G1/M1 owner slice as the program index directs. Task1.5
still requires real shared UI/AI publication/use. No rule deployment, temporal
pipeline, live model call or transit monitoring qualification is claimed.

G1 prerequisite checkpoint: device-service now has real native schema and row
owners with reviewed create/provision/describe, row CAS/history capture, LIVE
queries and NativeDatasetMetadata over verified SQL state. G6 consumes the native
owner APIs through the /data manual workbench; /data/language publishes the exact
common-vcl registry and the separate implemented owner profile. RULE
source/binding/grant adapters and bundle owner wiring remain required by1.4.
AI tool registration and full UI/AI parity remain required by1.5. Native readiness
must not produce synthetic RULE execution pins.

Native transport increment2.3: analytics-service now has a Spring HTTP adapter
over the ordinary signed native describe API and immutable VclMetadata snapshots.
Canonical schema identity, exact head/capability/scope pins, fresh reauthorization,
closed bounded parsing, redirects and absolute snapshot lifetime are covered by
7 focused HTTP/transport tests. The full analytics reactor run passes245 analytics
tests plus its shared-module tests; the final focused run covers the later strict
integer/read deadline assertions. Structure, VCL documentation and104/104 OpenSpec
checks pass. Tests use a controlled local HTTP owner, not end-to-end real database
or binding/grant qualification. Task1.4 remains open for complete RULE Owners and
bundle preparation wiring;1.5 still needs shared UI/AI invocation. No archive.

Source metadata increment2.4 is verified with22 H2/PostgreSQL signed owner cases
and6 analytics adversarial HTTP cases, including a real owner→shared transport→
common codec path. Full backend checkpoints pass1138 and1156 tests across16
modules; the final stored-fingerprint precision fix passes the221-case device
reactor. Profile and
capability decoding rejects fabricated legacy execution/negative-evidence flags;
source schema snapshots grant no resource rights. G0 task1.4 remains open for real
evidence-policy owner, normalized capture readiness and complete bundle
preparation wiring. RULE/output IDs and reviewed automation grants now exist in
alert-service under G4, including private review/confirm/inspect/reconcile and
local epoch revocation. Compiler grant adapter verification follows as2.5.
Actual alert/effect admission and normalized capture remain separate gates.
Reservations and permissions do not activate rules or close G0.

Grant adapter increment2.5: the ordinary signed alert-owner GET response is
strictly decoded with immutable exact field/revision membership and a positive
decimal-string epoch. Actor/account/rule replacement, expanded delivery/effect
authority, scope or epoch drift, revoked/expired grants and malformed/redirected
responses are rejected. All permission/pin uses renew the owner read; pins bind
identity/epoch/definition. Five adversarial HTTP cases plus42 real H2/PostgreSQL
owner grant cases pass, including actual controller→common codec round trips.
The full backend checkpoint passes1208 tests across16 modules without failures,
errors or skips (artifacts/g0-grant-adapter-backend-tests.log). This is compile
permission metadata, not an atomic autonomous effect permit or completed public
RULE bundle path. Continue evidence-policy/capture owners and1.4 bundle wiring.

Stop checkpoint2026-11-04: G3 schema publication and opt-in accepted Boolean/TEXT
input persistence are verified in the1250-test full backend reactor. They do not
supply capture generations, qualified upstream coverage or temporal runtime
readiness, so1.4 stays open. Evidence-policy2.6/G4 task4.4 has an explicit target
contract only; implementation has not started. Main1.5 remains open as well.

Resumed evidence-policy increment2.6 supersedes the previous stop checkpoint's
pending policy note. The reviewed private-reference owner and signed compiler
adapter are implemented and verified with40 H2/PostgreSQL owner cases and7 HTTP
adapter cases. The full backend passes1297 tests across16 modules without failures,
errors or skips. Structure, OpenSpec104/104 and VCL docs checks pass. Fresh policy
pins include its grant identity/epoch/scope, limits and expiry; actor/account/rule
replacement, disclosure expansion, unbound or extended policy lifetimes, drift,
revocation, malformed responses and redirects fail closed. Policy metadata does
not implement evidence retention or qualify runtime readiness. Continue capture
generation/readiness2.7 and owner/bundle composition1.4 before UI/AI1.5.

Capture adapter increment2.7: actual telemetry SQL generation/cutover is exposed
through its private readiness read and the freshly authorized ordinary source
owner capture description. Strict shared codecs reject false quality/runtime
claims, malformed tuple/counters, replaced schema pins and unknown fields.
SourceCaptureSnapshots refreshes signed actor/account/source authority on use,
preserves the captured head while permitting advancement, and rejects regression
or changed generation/cutover/schema/scope. Temporal and negative-evidence gates
remain closed. The clean focused regression passes88 cases and full backend
`mvn clean test` passes1323 tests across16 modules without failures/errors/skips.
G0 main1.4 remains open for owner composition, qualified operator/readiness/cost
checks and public bundle integration;1.5 UI/AI use is still pending.

Static owner composition checkpoint2026-11-05: RuleCompilationSnapshots derives
the exact read targets from one parsed RULE and composes actual signed source/
native/grant/policy adapters for shared STATIC_TYPED_IR compilation. The first
profile requires grant membership for every exposed schema field; partial grants
and unsupported literal resources remain unavailable. Source/native scope and
schema/head drift, actor/account/rule replacement, grant epoch/revocation, policy
drift, owner failure and unavailable as-of projection fail closed. The full
analytics reactor passes559 tests (479 analytics plus80 shared), including
H2/PostgreSQL bundle/state/timer/outbox regression. Two final added tests for
bounded grant batches and malformed document fields/methods pass in the19-case
focused follow-up (13 composition plus6 grant cases). This is not an unrun561-test
reactor or all-module backend claim. Structure, VCL docs and OpenSpec104/104 pass;
logs remain ignored in artifacts/g0-rule-composition-*. No public route, runtime
readiness/cost attestation, executable bundle or effect admission is introduced.
Task1.4 remains open for those gates and public bundle preparation;1.5 remains open.

Compose owner follow-up2026-11-05: scripts/configurable-owner-docker-smoke.py
passes against the actual gateway/identity/device/analytics/alert/telemetry stack
and PostgreSQL. It creates a separate synthetic account/source, provisions a
native table and two reviewed typed rows, verifies their exact LIVE values,
creates reviewed grant/policy metadata and invokes the shared static compiler
through the signed owner adapters in the analytics container. Unsupported ASOF
is rejected; actual grant revoke invalidates the same captured snapshot. The
ignored artifacts/configurable-owner-smoke-summary.json records PASS. No model
calls, real customer inputs, executable bundle or RULE activation are claimed.
