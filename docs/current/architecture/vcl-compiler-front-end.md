# VCL compiler front end

Implementation status, 2026-11-03: G0/M0 front end, all 21 scalar methods (circle
membership profile) and durable bundle infrastructure. The
[common-vcl module](../../../backend/common/common-vcl/pom.xml) is a pure Java21
library; no service endpoint, rule activation, temporal evaluator or database
write is enabled by adding it to the reactor.

## Implemented boundary

- VclParser strictly decodes UTF-8, rejects duplicate keys/trailing JSON/invalid
  Unicode/unsafe JSON numbers and enforces byte/depth/closed grammar limits.
- VclDocument holds a defensive immutable document snapshot for the six VCL kinds.
- VclRegistry derives scalar arities and graph ports/config schemas from the
  canonical grammar; capabilities expose21 scalar signatures and12 graph methods.
- VclType/VclUnits implement static types, enum/reference domains, unit checking,
  exact rational/affine unit conversion and registry fingerprints.
- VclCompiler builds a bounded typed DAG and checks field scopes, graph ports/
  cycles/reachability, session identity, lookups, temporal bounds, predicate types,
  import conversion chains, keys and mutation shape against VclMetadata.
- VclCanonical uses RFC8785 JCS with typed normalization. Typed DECIMAL is still
  a wire string; TEXT resembling a number/UUID is not reinterpreted. External
  source TEXT keys preserve exact normalization form. Display labels are omitted
  only at the documented locations from semanticDigest.
- VclScalarCompiler parses a scalar expression with the same closed grammar,
  applies the same bounded type/access/unit checker once and builds immutable
  VclScalarProgram nodes from its checked types. Only referenced record fields
  enter the program. REF literals require the owner metadata authorization port.
- analytics-service owns VclScalarEvaluator, ScalarValue and ScalarDecimalPolicy.
  These are pure Java kernels without Spring registration or owner I/O. All19
  non-spatial methods implement explicit OK/UNKNOWN/error outcomes, Kleene logic,
  known-null diagnostics, exact INT64 arithmetic and checked DECIMAL policy.
  Unit conversion retains rational precision until one output-scale rounding;
  duration_from rejects fractional/overflowing microseconds. No double arithmetic
  is used for numeric quantities. Known literals use the shared parser; TEXT is
  NFC and comparisons are case sensitive (ordering is by Unicode code point).
- distance_m and inside use the versioned WGS84 kernel and immutable local circle
  snapshots described below. Geometry calculations alone use double coordinates.
- CompilationBundlePreparation accepts trusted owner ports, checks a closed typed
  validation manifest and stores sealed IR through CompilationBundleStore.
  JdbcCompilationBundleStore implements insert-only persistence in analytics.

The authoritative grammar is packaged directly from
[the single contract file](../../target/configurable-operations-language.schema.json)
as a Maven resource. There is no manually synchronized second grammar. Only local
fragment `$ref` is permitted in this resource. Capability schema/config projections
come from that same registry; owner/AI HTTP publication remains later integration.
VclScalarContracts publishes the scalar/kernel profile through that registry.
registryDigest binds both grammar and scalar profile, preventing a kernel contract
change from silently matching an old scalar program fingerprint.

External dependency: `io.github.erdtman:java-json-canonicalization:1.1` supplies
[RFC8785 canonicalization](https://github.com/erdtman/java-json-canonicalization).
Jackson2 and the existing project validator version are used without Spring runtime
dependencies. Do not substitute an ad hoc sorted-map serializer for JCS.
analytics-service also pins MIT-licensed `net.sf.geographiclib:GeographicLib-Java:2.1`
for the [WGS84 inverse geodesic](https://geographiclib.sourceforge.io/html/java/net/sf/geographiclib/Geodesic.html).

## Owner integration contract

The Java entry flow is VclParser.parse → VclCompiler.compile(document, metadata).
VclMetadata must be supplied by an owner-authorized immutable snapshot adapter,
never by frontend/model request JSON. Dataset/binding lookups are revision-bound;
literal references and connection/grant/evidence identifiers require authorization.
Missing data/access fails closed. System record keys for collections without a
business key come from owner metadata.systemFields.recordIdFieldId.

VclCompilation has stage STATIC_TYPED_IR, canonical document, typed topological
nodes/config/links, document/semantic digests, compiler/registry versions, registry/
unit-registry digests, scope digest and pinned metadata dependency digests. Returned
JsonNode values/configs are defensive copies; the accepted graph cannot be edited
through an accessor. The result is not an activation token or stored owner bundle.

## Scalar entry flow

Use VclScalarCompiler.compile(expressionJson, authorizedFields, metadata), then
VclScalarEvaluator.evaluate(program, recordSnapshot, decimalPolicy, scopeDigest).
The owner supplies both the authorized type scope and already validated record
snapshot; neither is a grant derived from user/model JSON. This library entry
flow is not exposed as a new HTTP endpoint. At evaluation the scope, grammar and
unit fingerprints must match the compiled program. A mismatch fails closed;
this check does not replace fresh owner authorization or immutable bundle pins.

ScalarValue.known(parser, literalJson) strictly validates wire types, precision,
Unicode and date ranges. Missing fields become UNKNOWN(FIELD_MISSING,evidence).
Known NULL is accepted only for nullable fields, comparisons with NULL yield U,
is_known(NULL)=T and is_null(Unknown)=U. Errors remain errors even in false AND
error, true OR error, and is_known(error). Unknown diagnostics carry bounded
reason codes and evidence UUIDs; no customer value appears in diagnostics.
Decimal policy pins precision38, scale0..12 and REJECT or HALF_EVEN. Overflow
always fails; a nonterminating division with REJECT produces OPERATOR_ERROR.

## Spatial scalar profile

Wgs84ScalarKernel pins profile `geographiclib-java-2.1-wgs84-nm1-v1`. GeographicLib
2.1 computes the WGS84 inverse geodesic. The kernel explicitly quantizes metres
to 9 fractional digits with HALF_EVEN, with a conservative 0.000001m error bound
covering algorithm/coordinate representation/quantization. distance_m then applies
the rule's decimal policy; REJECT never silently drops output scale.

For inside, pass SpatialEvaluationContext with an expected kernel version,
snapshot digest, SpatialFeatureSnapshot and freshly owner-authorized feature IDs.
Point order is longitude/latitude. Circle radii are positive and at most 20100000m;
snapshots have at most 128 exact ID/revision/geometry pins. Digests use JCS, numeric
radius strings and features sorted by canonical UUID text. The snapshot is
immutable; fresh authorization is separate from historical geometry.

Effective uncertainty is reported accuracy plus the kernel error bound. For
distance d, radius r and uncertainty a: d+a<=r is T, d-a>r is F, otherwise U with
the feature ID as evidence. Negative accuracy is INVALID_INPUT. Null/unknown
accuracy is U, without a zero/default floor. Missing authorized revisions and
version/digest changes fail closed; denied references cannot be hidden by unknown
position, Boolean logic or is_known. There is no network or latest-geometry lookup.
The four-argument evaluator supports distance without feature access; inside
requires an explicitly authorized spatial context. Polygon membership is not
advertised. Session entry/exit/debounce and quality floors remain G4.

## Bundle persistence boundary

CompilationBundlePreparation compiles a RULE, asks trusted actor-scoped Owners
ports for readiness and reauthorization, and seals a versioned OWNER_VALIDATED_IR
envelope. Owner manifest checks exact document/scope/dependency pins, access and
readiness, lookup indexes, state/work estimates against owner budgets, grant and
evidence-policy revisions, constant spatial references, binding generations,
operator versions, key-definition digest, snapshot-set digest and start boundary.
These are owner attestations; the parser does not measure database index plans,
source completeness or cost. The bundle Owners adapters remain unwired. The first
real native dataset metadata adapter now exists in device-service: it reads
account authority and verified SQL catalog state for common compilation. Its
[review/provision/describe API](../api/configurable-native-data.md) is a G1/M1
prerequisite, not full binding/grant/readiness integration for RULE execution.

V034 creates vcl_compilation_bundles in analytics. The JDBC adapter is insert-only:
account+bundle ID identifies original canonical bytes; same bytes replay, a new
payload returns BUNDLE_ID_REUSED. Reads are scoped by account and verify version,
SHA-256, canonical bytes and envelope identity. Corruption fails rather than being
repaired/recompiled. Created time is assigned by the database and remains unchanged
on replay. The adapter owns a one-statement autocommit connection. No UPDATE/DELETE
application method exists; administrative SQL is outside this API and corruption
tests deliberately use it to prove detection. This is not a DB-admin tamper seal.

The application inspect method reauthorizes before returning private bundle data.
There is no HTTP route/Spring bean, active-rule worker or activation token for this
boundary yet. Hydrated payload is data; execution must resolve the stored compiler,
operator and resource versions explicitly, never compile against latest metadata.

analytics-service now provides NativeDatasetSnapshots as a Spring HTTP adapter
for the ordinary signed `GET /data/datasets/{id}?schemaRevision=...` owner API.
It captures 1..64 distinct pinned native schemas for one actor and supplies
NativeDatasetSnapshot to the common compiler. Credentials stay in the short-lived
adapter closure, never in metadata, digests or diagnostic strings. Owner responses
are size-bounded, strictly parsed, checked against the requested identity and
reconstructed canonical COLLECTION digest. Redirects are disabled; connect/read
timeouts reuse `app.device-service.timeout`. The snapshot has a non-renewable
20-second lifetime, checked before each owner request.

Compiler scope fences and dataset inspection refresh the original descriptions.
Revocation, missing resources, changed scope, generation, dataVersion, schema,
indexes or capabilities fail closed; the adapter never substitutes newer pins.
It preserves the owner's explicit LIVE/history capabilities and grants no binding,
reference or execution authority. This is the native metadata increment of G0
task1.4, not a wired RULE preparation/activation endpoint. Its HTTP tests use a
local controlled owner server; they are not end-to-end PostgreSQL/binding/grant
qualification. Signed native owner/storage tests remain owned by device-service.

SourceBindingSnapshots additionally consumes the source-owned immutable journal
through common-web's fixed signed read transport. The common-vcl wire codec and
VclSourceBindingProfile are shared with device publication. Expected actor/account,
current schema revision, source activity, scope and all original snapshot bytes
are fenced on refresh. This supplies source typing metadata, not capture readiness:
the legacy profile cannot satisfy requireRuntimeReady or grant any resource right.
The source journal/transport path is checked against actual signed H2/PostgreSQL
owner endpoints; adversarial analytics HTTP tests separately cover malformed
responses, identity replacement, revocation, drift and forbidden readiness claims.

The alert owner now implements reviewed exact source/native-field grants and epoch
revocation. `AutomationGrantSnapshots` reads its ordinary signed GET route and
strictly decodes the closed `source-native-fields-grant-1` profile. Actor/account/
rule identity, permission lifetime, exact fields/revisions and ALERT-only authority
are checked before use. Each permission check and resource pin refreshes the owner;
changed scope or epoch invalidates the captured snapshot. The resource digest binds
identity and epoch as well as authored scope. The20-second snapshot lifetime is a
compile fence, not an autonomous effect permit; it does not store a user token as
execution identity. Public RULE bundle composition remains pending.

EvidencePolicySnapshots now obtains the real alert owner's reviewed private-reference
policy over the ordinary signed GET contract. Closed profile decoding checks exact
integer budgets, canonical definition hash, identity and usability. Every pin use
refreshes the policy and its separately authenticated grant snapshot; different
grant epochs/scope digests, expanded disclosure or extended lifetimes are rejected.
Resource pins include both policy limits and grant identity/epoch. These are owner
metadata checks, not evidence persistence or retention execution.

SourceCaptureSnapshots now reads the separately signed source capture description,
bound to actual persisted telemetry generation/cutover and a fresh source/account
fence. Generation is assigned only by real accepted writes and never by schema
metadata or inspection. Head progression is allowed; generation/cutover change,
retargeting and checkpoint rollback invalidate the captured pins. The interface
still denies temporal runtime and negative-evidence readiness and grants no
resource rights. The captured checkpoint is not a sealed row snapshot.

analytics-service also provides pure `VclWindowPlan` assignment from compiled
`window_aggregate` nodes. The versioned plan assigns exact microsecond FIXED/
ROLLING windows (maximum31 elapsed days and64 rolling memberships) and local
DAY/WEEK/MONTH calendar windows. Calendar months include actual offset changes
and may exceed31 elapsed days within the32-day bound. ISO week starts and
half-open boundaries are explicit. Plans pin server timezone rules and bind them
in the digest; replay requires both original pins. Missing/submicrosecond times,
unsupported bounds and changed timezone rules fail closed. No wall clock or
floating-point timestamp arithmetic participates in assignment.

This is an interval-assignment kernel, not an aggregate or durable temporal
runtime. It supplies no source coverage, watermarks, evidence completeness,
activation authority or alert effects. Owner/bundle integration and the remaining
G4 runtime gates still apply.

`VclIntervalUnionKernel` supplies the next pure arithmetic step: union of included
finite intervals minus the union of exclusions intersecting that work, clipped to
an explicit half-open window. Nested/overlapping/adjacent intervals count elapsed
microseconds once. Exclusions outside work do not subtract duration. A replacement
batch can recompute a corrected result without mutating the original inputs.
Predicates are typed ScalarValue results from the shared scalar evaluator; raw
expressions/scripts are not accepted by this kernel. Unknown/null membership,
unknown selected interval values and selected open ends produce UNKNOWN with
bounded evidence references. An interval with both predicates FALSE is unselected;
infrastructure failures and malformed known literals are still errors.

The `vcl-interval-union-1` context pins kernel identity, an owner scope digest and
the exact window. The caller must supply fresh authorization and an already
partitioned batch for one subject/window at the intended reference cutoff.
The kernel compares scope/version pins; it performs no owner I/O or authority
renewal. Limits are4096 observations,128 unknown evidence references and32 elapsed
days per window; overflow fails without publishing a partial result. Observation
IDs must be unique in a batch. This is not durable inbox deduplication.

A known duration, including zero for an empty supplied batch, measures only that
batch. It is not a complete-window result, absence proof or lower-bound emission
permit. Source/reference/break completeness, interval eligibility and assignment
splits, calendar-plan pins, finalized watermarks, durable state/corrections and
alert/effect admission still belong to the pending runtime. Neither this kernel
nor interval assignment is advertised as a deployed temporal operator.

`VclWindowAggregateKernel` projects a shared compiled `window_aggregate` node and
its predecessor's typed value field into an immutable reduction plan. The plan
checks the standard registry/unit fingerprints and output type, and pins its
configuration, decimal policy, semantic graph digest, authorization scope and
window/timezone plan. Evaluation requires matching kernel/plan/scope pins and an
actual window from that plan. It accepts at most4096 unique observation IDs in
an already selected single-subject batch. Same-timestamp events remain distinct;
durable deduplication and upstream filter eligibility belong to the runtime.

COUNT has no value field and counts in-window events. SUM/AVG preserve units;
INT64 AVG produces DECIMAL, while DURATION AVG requires exact integral
microseconds. SUM rejects absolute temperature units. Exact bounded sums are
order independent; decimal scale rounding/precision and integral range checks
apply to the final reduction, without intermediate rounding/overflow from input
order. MIN/MAX preserve the ordered type and use Unicode code-point text order.
Known values are validated even outside the window; infrastructure failures
remain errors. Selected unknown/nullable-null values produce UNKNOWN with up to128
deterministic evidence references. Nonnullable NULL fails. Empty COUNT/SUM return
their arithmetic zero; empty MIN/MAX/AVG return UNKNOWN(AGGREGATE_EMPTY).

These are arithmetic results over supplied batches, including replacement batches
for correction calculations. They confer no source/reference completeness,
finalization, lower-bound proof or emission authority. Coverage/watermarks, actual
grouping, durable correction/state and atomic alert admission remain pending;
`vcl-window-aggregate-1` is not advertised as a deployable temporal operator.

`VclDurationWitnessKernel` projects compiled `observed_within`/`sustained` nodes
into one pinned pure supplied-batch duration plan. A zero threshold requires an
actual eligible TRUE observation; positive duration requires an explicit
`bounded-sample-hold-1` contract within operator/root maxGap and adjacent actual
observation endpoints. It never extrapolates after the last sample. Known false,
unknown and excessive gaps break continuity. Conflicting equal-time facts cannot
arbitrarily establish duration, but do not erase an actual zero-duration witness.
The half-open window's end can close a hold, not create a new instantaneous
witness. No proven witness yields UNKNOWN, never proof of absence. Full key tuple,
scope/version/plan/time fences,4096 inputs and128 evidence references bound the
arithmetic. Source coverage/finalization, actual session/occurrence ownership,
durable transitions/corrections and effect admission remain separate gates.

`VclTimingPlan` is the pure `vcl-timing-1` arithmetic projection of root RULE
timing. It verifies the canonical document/semantic digests and matching
absent_within lateness, and pins the exact BINDING/DATASET dependency set and
authorization scope. Each input identity has one positive revision and a
valid snapshot digest. Future skew compares exact event and trusted owner accepted
timestamps, including microseconds at the allowed boundary. Overskew progress
is quarantined. There is no local wall-clock read or overflow-prone timestamp
addition. Late-input admission/correction remains a separate runtime decision.

Given a complete supplied set of qualified trusted source/reference progress,
the plan computes its minimum minus root lateness exactly once. Unknown progress
produces UNKNOWN; missing/duplicate/changed pins and invalid/future progress fail.
A frontier below the supported year1 boundary stays UNKNOWN. Idle expiration
uses its own exact elapsed deadline and rejects a backward clock; expiration
requests an UNKNOWN wait outcome and never proves exit or covered absence.

No current metadata owner is thereby qualified to supply trusted runtime progress.
This plan performs no generation/lease/checkpoint persistence, monotone-progress
enforcement, coverage/reference completeness checks, timers or effect admission.
Its known INSTANT is a time frontier over supplied trusted facts, not a finalized
window acknowledgement or an activation capability. The future runtime must bind
actual generations and owner evidence and retain the remaining negative-evidence
gates even when the arithmetic frontier reaches a window end.

`KeyedStateStore` and `JdbcKeyedStateStore` provide isolated state/inbox storage
infrastructure, with V035 tables `vcl_keyed_state` and `vcl_keyed_inbox`. They are
not Spring beans and expose no HTTP route, activated worker or dispatch loop.
Trusted application callers must first authorize actual deployment, generation,
grant and account scope; a pins digest or account UUID is not authority.

A prepared direct journal graph stages a historical measure, its typed
`compare` decision and the close timer through this store. The execution pins
the graph, retained input, timing, window and optional owner proof. After the
database reports the timer due, consumption writes one immutable `MEASURE`
output with the decision and terminal node identity. Rebuilding the executor
preserves the receipt and does not enqueue another result. The output carries
the coverage outcome and `finalized=false`. A persistent dispatcher and alert
effects remain separate stages.

The executor saves its recovery recipe in private keyed state. Recovery loads
the canonical rule and original cut from SQL, uses fresh signed owner
authorization, and recomputes the execution pins. A later journal head is
allowed while the original cut remains readable. A missing or changed cut
fails recovery. The measure queue carries no recovery recipe or credentials.

`consumeDueWindows` takes a fresh caller authorization and scans a bounded
account-scoped due set. Each WINDOW_CLOSE candidate is rebuilt from its private
recipe and original cut; the restored key, pins, timer identity and scheduled
revision must match before lease-backed consumption. A revoked grant leaves the
timer pending, and a repeated drain does not emit a second measure.

The opt-in machine diagnostic scheduler scans ACTIVE deployments and renews
owner authorization before recovering each due window. Its timer pages contain
only WINDOW_CLOSE candidates for the exact account, rule and deployment epoch.
The worker advances through the due set after failed recovery and retries
pending candidates after wrapping around. Its cursor cache is local to each
worker and bounded to 128 deployments. Results carry `finalized=false`.
The machine commit locks the exact deployment row before the keyed state row,
serializing diagnostic writes with local deployment revocation and cutover.
The registry can transfer the active slot at an authority-checked boundary.
The diagnostic worker recovers windows before that boundary on the draining
predecessor and windows starting at or after it on the successor.

Keys bind account, logical rule, deployment epoch, subject and occurrence/window
digests. The full key tuple is retained and checked alongside the physical hash;
it is not reconstructed by reversing a hash. Registration pins initial state and the caller's immutable execution-pin
digest; identical registration replay returns current state without resetting it.
State uses canonical bounded JSON objects (64KiB UTF-8, depth32, numeric values
represented by exact typed strings). Restore checks canonical bytes and SHA-256.
The profile limits each key to4096 committed input revisions and fails closed
when exhausted; global account quotas/retention/compaction are not supplied here.

Acquisition grants a30-second database-clock lease, monotonically incrementing
the fence even on same-worker renewal. Lease identity includes the exact key and
pin digest. Competing holders cannot acquire an unexpired lease. Commit locks the
key, reads fresh database time after any lock wait, and checks holder/fence/expiry,
expected state revision and inbox budget. State/revision/inbox counter and the
immutable input-revision receipt commit atomically. An exact duplicate transition
returns its original revision, including after a lost ACK/lease expiry, without
another state change. Changed input/transition bytes conflict. A correction uses
an explicit new input revision and current CAS revision; this is not historical
evidence retention or correction propagation.

SQL statements have a5-second JDBC query timeout; keyed transactions additionally
limit lock waits to2seconds (PostgreSQL transaction-local, H2 session-local).
Timeout returns a storage-unavailable outcome after rollback; a caller retries
only its original immutable intent and never assumes a lost ACK proved failure.

Timer execution, derived/rekey dispatch, outbox publication, lease scheduling, activation/generation/
grant-owner checks and typed evaluator transitions remain pending. This adapter
must not be used as a complete durable RULE transaction until those effects share
the same fenced commit. No existing numeric variable-rule tables are replaced.

V036 adds `vcl_keyed_timers`: a complete next timer schedule is replaced inside
the same locked state/inbox transaction. There are at most64 timers and256KiB of
canonical timer payload per key. Each timer has an exact microsecond due time,
one typed intention (WAIT_TIMEOUT/WINDOW_CLOSE/SESSION_TIMEOUT), stable UUID and
the state revision that scheduled it. Reacquiring a key returns its consistent
current schedule alongside state. Timer input receipts occupy a separate inbox
namespace from observations, including when their UUID/revision tuples coincide.

`dueTimers(accountId,limit)` discovers at most128 candidates using fresh database
time and an account/due-time index. It neither claims a lease nor dispatches a
callback. A worker must reacquire the key, refresh actual owner/generation/grant
authority and consume the exact current schedule revision. Commit checks the
saved timer digest, due time, lease and state CAS, then replaces the schedule and
commits state/inbox together. Correcting or removing a timer invalidates an old
candidate even if its previous due time has passed. A consumed timer cannot rearm
itself in that transition. Exact replay returns its original receipt after timer
removal; a changed schedule/body conflicts. Ordinary state-only transitions declare
an empty next schedule. Timer ordering does not affect the transition digest.

These are durable timer intents, not an activated scheduler or coverage proof.
Expiration must still flow through the qualified root timing and UNKNOWN/coverage
contracts. No stored WINDOW_CLOSE/SESSION_TIMEOUT grants a finalized window or
observed exit. External outbox/derived/rekey effects and actual evaluator/owner admission
remain open under G4/5.6.

V037 adds `vcl_keyed_outbox` to the same state/timer/input transaction. A transition
can append at most64 typed immutable output intents (MEASURE, REKEY or
ALERT_PROPOSAL), with a combined256KiB timer/output payload budget. Each key
retains at most4096 output identities, including acknowledged entries. Exact
output ID/body reuse retains the original record/revision without republishing;
changed body/target/kind conflicts. REKEY carries the full target key and cannot
cross its source account, logical rule or deployment epoch. This is metadata,
not an internal rekey consumer or actual derived/alert effect.

Private pending/inspection reads validate the source key/pins and stored canonical
payload/digest/revision. Acknowledgements bind exact output digest, owning receipt
UUID/digest, fresh lease and database clock. Same acknowledgement is read-only;
changed receipt identity conflicts. ACK batches are atomic, preserve state
revision and never remove retained deduplication evidence. After a lost local ACK
commit, inspection recovers the original owning receipt metadata. Fresh lease
time is rechecked after all transition/ACK SQL before committing; expiry rolls
back state, timers, input receipts and outputs together.

V038 adds atomic local rekey consumption through `KeyedStateStore.consumeRekey`.
The source must contain the exact retained REKEY output targeting the full target
key. Input identity binds source key, output digest and original output revision;
equal output UUIDs from different sources cannot collide with one another or
ordinary observation/timer receipts. Both keys must use the same execution pins.
SQL locks follow digest order in both directions, with fresh database-clock lease
checks before and after work. Target state/CAS, complete timer replacement, output
intents, immutable local receipt and source ACK commit or roll back together.
The per-target inbox budget includes local rekeys; source deduplication/ACK records
remain retained. Self-rekey uses one key lock. Exact replay verifies the stored
local receipt and matching source ACK before returning its original revision,
including after restart/lost ACK/lease expiry. An arbitrary external ACK cannot
be substituted for that local receipt.

V039 adds integrity-checked input/output-set digests to new keyed inbox receipts.
Legacy rows keep their null digests and exact-transition replay. `inputReceipt`
inspection verifies the namespaced receipt, state revision and integrity hash.
It never backfills old input/result bindings.

`DurableDurationWitness` now uses these receipts and the shared duration kernel
for a bounded single-witness keyed profile. The full rule/partition, plan/hold,
window and execution pins remain fixed. Up to128 latest observation revisions
fit within the existing64KiB state limit; exhaustion rejects rather than evicts.
Corrections recompute the batch and append a new MEASURE result intent. Prior
input/result receipts remain immutable. After lost commit ACK or a later
correction, exact replay verifies the original input and output-set digests and
returns that revision's original result. A post-acquisition receipt check handles
concurrent first commits. State, input receipt and result intent commit or roll
back together. Invalid scope/rule/input fails before registration; legacy receipts
cannot be silently upgraded into this stronger replay profile.

This is an isolated durable transition profile with an empty timer schedule,
not an autonomous graph worker or actual source/grant/generation/coverage
admission. Its MEASURE intents do not materialize derived datasets or authorize
alert effects. Actual owner fencing remains mandatory before autonomous use.

The rekey primitive consumes a supplied target transition; it does not calculate a
typed evaluator transition or authorize owner/generation/coverage admission.
There is no autonomous rekey worker, Kafka publisher, notification port call or external sender here.
Future application adapters must validate real owning receipts and fresh grant/
generation/coverage authority; supplied receipt hashes are not proof of an effect
or authority. Account-wide quotas, retention/compaction, actual derived/rekey/
alert admission and typed evaluator execution remain pending under G4/5.6.

`RuleCompilationSnapshots` now composes the signed source/native descriptions,
reviewed grant and grant-bound evidence policy for one parsed RULE. It derives
targets from the closed document, rejects conflicting revisions before owner I/O,
and binds the exact rule/actor/account. The document-bound snapshot invokes the
shared compiler and returns only `STATIC_TYPED_IR`. Its scope binds the common
source/native owner scope plus immutable grant/policy pins. Every compiler use
refreshes owner authority, schema/data heads and grant/policy epochs; changes
invalidate the snapshot rather than silently replacing its metadata.

The initial composition requires grant membership for every exposed schema field:
`read_stream` exposes the source schema, and lookup metadata exposes its full
schema. A partial field grant is rejected; this profile does not implement field
projection or field sharing. Bounded field checks renew the grant once per schema,
rather than once per field. Native LIVE metadata cannot fabricate an as-of
projection. Literal resource references remain unavailable in this profile.
This component introduces no route, bundle admission attestation, worker or rule
activation. Controlled HTTP tests cover adversarial owner responses. The separate
Compose smoke exercises ordinary signed owner APIs and PostgreSQL with synthetic
fixtures: native provisioning, two reviewed rows/exact LIVE values, grant/policy
reviews, static compilation, unsupported ASOF rejection and real grant revocation.
It does not qualify executable bundles, live customer inputs or model quality.

Still required: complete qualified capture/runtime readiness
and fresh row/source CAS; actual
source/adapter capability/index/cost/readiness checks; ordinary owner/UI/AI
publication, shared invocation and persisted review/apply integration. Import checks here validate
the declaration and an owner-supplied artifact hash, not file rows or database import
execution. Temporal sessions/windows/absence/replay remain G4, operational capture
G3 and HSL occurrence binding G5. No Spring AI request uses this module yet.

## Verification

SourceCaptureSnapshot.readPage reads actual accepted Boolean/TEXT facts through
the signed source owner using the original generation/head cut. The shared closed
request/page contracts preserve exact schema fields, contiguous checkpoints,
same-time facts and bounded row/byte limits. Fresh owner checks surround remote
reads; telemetry verifies stored canonical-payload checksums without backfilling
legacy rows. Head advancement does not retarget the snapshot. The
[ordinary source API](../api/configurable-native-data.md#accepted-observation-prefix-reads)
still reports UNKNOWN coverage, UNQUALIFIED clocks and false negative-evidence/
RULE readiness. These pages supply data, not executable bundle admission.

`RuleCompilationSnapshots.captureJournal` binds static RULE compilation to the
original retained cut of each referenced source. It sums the checkpoint spans
against a positive input-count limit supplied by the internal caller. It refreshes
source, native, grant and policy ownership and applies the same exact field
checks as ordinary static compilation. Later checkpoint advances preserve the
original compilation identity. A new cut changes that identity, and checkpoint
regression or a generation change invalidates the snapshot. The result remains
`STATIC_TYPED_IR` with the journal's conservative coverage and clock guarantees.
State size, evaluator work and operator readiness require separate validation.

`prepareJournalWindowBatch` checks one `window_aggregate` with a direct
`read_stream` predecessor. It builds the real reducer plan with the rule's
decimal policy and pins the assignment plan, including its timezone rules.
For the original cut it enforces the reducer's input limit and bounds total
window memberships by multiplying the retained row count by the plan's maximum
fan-out. This is a conservative input-work bound for a pure batch. Coverage,
finalization and durable execution remain separate.
The prepared batch reads pages from the same original cut. Source, grant and
policy checks bracket each read, so a revocation during I/O prevents the page
from reaching the caller.

`readRetainedInput` reads from the generation start checkpoint through the
original cut. It preserves validated page boundaries and checks event identity
uniqueness across pages, the exact expected record count and a total canonical
encoded-byte limit. The accepted budget range is 1 byte through 8 MiB,
validated before journal requests. A failed page or ownership check prevents the entire batch
from being returned. The cut digest identifies the local checkpoint range. A
separate digest binds its canonical records in checkpoint order regardless of
page boundaries. Upstream coverage and event-time provenance remain the original
page values.

`JournalWindowBatch.preview` runs the shared reducer for one exact subject
and one window from the pinned assignment plan. It returns the local scalar
result and the input digest. A missing subject inside the window or a selected
observation beyond the rule's future-skew bound returns UNKNOWN with an event
reference. The preview reports UNKNOWN upstream coverage, UNQUALIFIED event
time and false finalization.

Bundle preparation refreshes owner authority before writing and before returning
the stored bundle. If authority is revoked during the write, preparation returns
the owner error. The immutable row remains for authorized replay and inspection.

`compileWithReadySources` runs the shared static and grant checks before asking
the pinned source owners for runtime readiness. The metadata-only profile
returns `SOURCE_CAPTURE_UNAVAILABLE`. The current journal profile returns
`SOURCE_RUNTIME_UNAVAILABLE`, even after successful static compilation and
retained input reads. This check is a prerequisite for bundle admission.

Bundle restore decodes the stored typed IR and owner manifest, reapplies the
sealing checks and requires identical canonical bytes. Sealing and restore
check graph node IDs, methods, configs and input links against the canonical
RULE and require a topological order ending at its declared terminal. Restore
also verifies that schema dependencies cover exactly the binding and dataset
revisions referenced by the graph and carry valid snapshot digests. It uses
the saved versions without an owner metadata lookup or recompilation.

```powershell
cd backend
mvn -pl common/common-vcl -am test
# Scalar execution and numeric AST compatibility belong to analytics-service:
mvn -pl services/analytics-service -am test
# With the ordinary Compose gateway/identity/device/telemetry/analytics/alert stack running:
cd ..
python scripts/configurable-owner-docker-smoke.py
```

Run the Python command from the repository root. It creates a distinct synthetic
account/device/native table with two rows and revokes its test grant. Fixture IDs
and results are saved under ignored `artifacts/configurable-owner-smoke-summary.json`;
credentials stay in memory/stdin. The Java helper runs inside the analytics
container against the real device/alert owners. No AI model request or activation
is dispatched. The synthetic resources remain for inspection.

63 tests pass:11 positive contract documents,33 negative grammar/semantic vectors
plus independent precision/encoding/unit/access/immutability/limit/key tests.
Additional compiler checks cover stale data/scope, exact source TEXT keys, non-business
system identity, enum typos and overlapping boolean tokens. Tests use synthetic
metadata, not real tenant authorization/network/source capability evidence.

VclScalarEvaluatorTest has43 passing cases and independently covers the complete
AND/OR/NOT truth tables,
null/unknown/error diagnostics, missing/denied fields, INT64 boundaries, decimal
rounding/division/overflow, rational/affine conversions, exact durations, text/time
normalization, scope/version fences, malformed/deep input and defensive snapshots.
VclSpatialKernelTest adds 24 cases: published/analytic geodesic vectors, poles,
antipodes, antimeridian, independent 30m circle vectors, exact uncertainty edges,
unknown accuracy, authorization/version/snapshot fences and old-revision replay.
CompilationBundleValidationTest and shared H2/PostgreSQL store contracts verify
readiness rejection, reauthorization, exact replay, conflicting payloads, concurrent
single-winner inserts, account isolation and corruption/version rejection. Docker
PostgreSQL is required, not skipped when absent. Owner adapters in these tests are
synthetic; they do not qualify cross-service grants or a deployed rule pipeline.

The full backend run passes 1047 tests across all 16 reactor modules, with no
failures/errors/skips, including full H2/PostgreSQL migrations, Kafka and numeric
AST compatibility. Final focused verification includes 63 compiler, 43 ordinary
scalar, 24 geometry and 29 bundle cases. Replay preparation reauthorizes the stored
manifest and returns its original bundle without fetching current metadata; a
changed document under the same ID is rejected. Logs remain ignored in artifacts.

The separate `npm run vcl:check` remains a documentation consistency guard, not
the implementation of this Java compiler. G0 owner/UI/AI integration remains open;
[the target language](../../target/configurable-operations-language.md) and
[active change](../../../openspec/changes/define-vcl-language-contracts/tasks.md)
remain authoritative for required completion.
