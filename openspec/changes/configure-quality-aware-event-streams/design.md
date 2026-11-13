# Design: Configurable normalized observations and capture quality

The [shared configurable architecture](../../../docs/target/configurable-operations-platform.md) owns canonical semantics and service boundaries. Implementation proceeds through its resumed vertical milestones.

## Implemented source schema prerequisite

V027/device-service record immutable source binding metadata in the ordinary source
write transaction, with stable field IDs and exact revision/snapshot hashes. Signed
describe checks current SQL ownership and state and never repairs on read. The
legacy schema profile admits explicit Boolean/text fields, retains numeric mapping
gaps and declares no normalized capture/negative evidence or RULE readiness. Shared
common-web transport and common-vcl decoding preserve those limits in analytics.
This is task4.1 and G0 task2.4, not completion of observation delivery or reviewed
mapping. See [the current contract](../../../docs/current/api/configurable-native-data.md#source-binding-schema-journal).

## Next accepted-observation prerequisites

Use the actual immutable schema journal to publish a sanitized binding schema event
through the existing device outbox. A server-owned source mapping key carries only
its exact schema pin; authored keys cannot replace it. Ingestion resolves that pin
from its source owner response/configuration, never from incoming metric/context
JSON. Carry it as a separate optional raw-event field, preserving legacy wire and
numeric AST behavior. A schema pin is not an input generation, capture readiness
ACK or coverage certificate.

Telemetry accepted observations retain exact event identity and typed Boolean/TEXT
values separately from sampled/latest views and timestamp deduplication. Missing
values and upstream coverage remain UNKNOWN. Receipt/checkpoint/outbox persistence
must be local and atomic; changing a payload under an existing event identity is
a conflict. Do not advertise negative evidence, complete transitions, bounded
upstream gaps or runtime readiness from an accepted-event journal alone.

## Implementation Boundary

The first durable journal profile was `accepted-boolean-text-1`, explicitly selected
by server configuration and disabled by default. V018 stores dense complete event
payloads, exact serialized-input hashes, local per-binding checkpoints and pending
inputs. Source schema envelopes are verified and retained by binding/revision.
Recovery uses the original stored input and pin, not a latest source schema. Raw
capture failures/quotas hold delivery; schema corruption holds its partition.
Outbox and facts/receipts/checkpoints commit together. Existing device deletion
fences prevent resurrecting schemas or facts. This is not native COLLECTION EAV
storage and does not copy source time series into current configuration rows.

Two distinct input IDs with equal timestamps must survive legacy measurement
coalescing. Under this opt-in profile, the legacy timestamp duplicate insert is
skipped so its PostgreSQL unique-key failure cannot roll back the second observed
fact. Numeric AST and unselected legacy paths keep their existing behavior.
Unavailable configured inputs are explicitly UNKNOWN; the legacy input policy
does not distinguish original null from absence. Exact numeric conversions,
upstream gaps/completeness, capture generations, reviewed capture authoring and
temporal readiness remain pending. No source/clock/coverage authority is inferred
from a schema publication or preserved accepted input.

**Normative complete design (2026-11-03):**
Use the normative ImportDefinition/SyncDefinition conversion and validation manifests. G3 normalizes observations, not arbitrary source SQL. A source role cannot declare confirmed employee work from motion metadata without an approved semantic mapping. Checkpoint/gap/clock quality is included in event evidence and projected selection.

Contracts: [VCL](../../../docs/target/configurable-operations-language.md),
[storage/adapters](../../../docs/target/configurable-operations-storage.md),
[strict imports](../../../docs/target/configurable-operations-imports.md) and
[acceptance A01–A66](../../../docs/target/configurable-operations-acceptance.md).
These decisions supersede tentative storage/interpreter wording below.

Keep source-specific parsing in adapters and generic fields/entity refs/units/occurredAt/quality in canonical observation contracts. Preserve transitions/interval identity across retry, keep explicit gaps and UNKNOWN, and retain latest sampling as a separate capability. Existing per-message numeric AST semantics are unchanged. Use existing outbox/inbox/source authorization; no network access from formula evaluation.

## Compatibility and Validation

### Source mapping preview

The signed preview reads one current source schema revision, validates a sample
against the selected field, then rechecks source authority. It supports identity
for BOOLEAN and TEXT and exact TEXT token-to-BOOLEAN conversion. The token
contract is shared with VCL import checking. The preview digest pins schema,
scope, field and conversion. Preview has no write effects.

Signed confirmation of a token conversion uses the preview digest and the
source owner's locked current revision. It records a receipt and publishes
the new schema and runtime configuration in the same transaction. The receipt
preserves the bound device, original conversion and applied schema pin.
Ingestion evaluates the conversion before constructing the typed descriptor.

Replacement uses the current schema and an exact new digest. Replay of a raw
field selects the latest receipt at or before the requested schema revision,
checks its applied snapshot and the original review digest, then evaluates
the stored conversion. Current ownership is required for historical reads.

New schema snapshots retain the exact telemetry policy and reviewed conversions
under a separate digest bound to the schema snapshot ID. Full raw-event replay
uses the shared live-ingestion normalizer with the retained policy. It returns
normalized metrics under the original schema pin through the owner API.
Legacy snapshots without archived policies cannot serve full replay.

### Producer position evidence

Authenticated live HTTP ingestion can carry an explicit producer epoch and
decimal-string sequence. The server attaches it to the actual source binding
snapshot. Telemetry commits position uniqueness and contiguous ranges with the
accepted journal row and outbox. The internal owner read returns the prefix
from sequence 1, the highest observed sequence and whether a gap remains.
Input generation, snapshot and epoch scope each result. Position continuity
alone supplies no event-time or transition coverage, which remains UNKNOWN.

### Trusted coverage attestations

A dedicated internal credential authorizes exact event-time coverage
statements from an upstream completeness attestor. The journal evaluates
the statement under the same binding lock as accepted observations and
persists covered statements and statements waiting for a missing producer
position. A pending statement remains UNKNOWN until the referenced prefix is
present and the retained interval contains no contradictory events. Reads
re-evaluate the original proof with its pinned generation and schema. Contradictions,
unavailable sources and insufficient evidence yield UNKNOWN. The regular
observation and readiness responses retain their existing conservative flags.

An operator CLI can submit an authoritative closed producer prefix after ordinary
ingestion. It checks an immutable bounded evidence file, pins the signed source
schema and input generation, stores retry identity without credentials, and
renews owner access after the attestation write. This qualifies no sampled feed:
the producer must independently guarantee that its closed prefix includes every
required transition. The synthetic source Docker acceptance writes its closed
producer log before ingestion and proves gap, restart, recovery and late
contradiction through the owner path. Production connector qualification remains
open.

The HSL vehicle feed and other sampled/polled adapters cannot be used as the
first qualified production source: a quiet interval does not establish that
every short transition or deletion was delivered. Task 4.17 requires a
producer-controlled durable event log and a credible closed-prefix boundary
for that source, with gap/replay/restart/late-event acceptance using the same
operator attestor path. Coverage for sampled adapters stays UNKNOWN.

The selected first production candidate is an existing REST source with a full
producer journal. Qualification is scoped to one source binding, schema snapshot,
input generation and producer epoch. Its own authenticated API must expose a
durable, immutable sequence-ordered prefix with original event times and an
explicit closed-through event-time boundary. Closure must mean that every
required transition with event time before that boundary was appended before
the prefix closed, including deletes and corrections; a last-seen timestamp,
empty poll, successful HTTP response or VoltCanary's contiguous accepted
positions is insufficient. The adapter must pin the producer's journal identity,
prefix digest and closure identity before submitting the existing statement,
then renew those identities after the owner proof read. A changed prefix or
retroactive event invalidates the claim. Restart, duplicate delivery, a missing
position, a delayed event and source revocation need acceptance evidence from
that real REST API before this connector is marked qualified. Until its endpoint
and lifecycle contract are verified, task 4.17 remains open and its coverage is
UNKNOWN.

Signed proof reads recheck current source ownership around telemetry I/O.
Analytics compares the proof ID, snapshot and generation with the prepared
source and rechecks the rule grant. A proof requiring a checkpoint beyond
the original captured head cannot cover a historical evaluation.

### Bounded accepted-journal reads

The accepted-observation-page-1 profile reads a fixed accepted generation/prefix
with exact schema pins and decimal-string checkpoints. Private telemetry reads
use a mandatory token, repeatable-read SQL and the existing binding/generation/
checkpoint index. They never create generations or repair data. Requests contain
the full pin, inputGeneration, afterCheckpoint, throughCheckpoint and limit1..128.
The cut must lie within the actual generation cutover/head. Missing accepted
facts, mismatched input receipts, malformed stored payloads or mixed schema pins
fail explicitly rather than becoming empty pages. At most240000 row bytes and
262144 response bytes are returned; a byte-limited prefix carries PAGE_BYTES.
Equal timestamps remain distinct accepted facts.

Accepted receipt validation checks the raw input digest format and requires the
pending payload to have been cleared in the acceptance transaction.

V020 adds a canonical payload digest atomically for new accepted facts. Reads
verify it before returning values; valid-looking Boolean/text changes cannot
evade integrity checks. Existing rows retain nullable digests. Neither read nor
original event replay backfills historical integrity evidence; an unbound prefix
fails explicitly while original payload/generation/replay semantics remain intact.

The signed source owner derives current source/account/schema authority from its
catalogue before the RPC and renews it after the RPC. A caller pin is only a
precondition, never account authority. Returned field IDs/types must match the
separately authenticated source schema. Analytics SourceCaptureSnapshots uses
the same signed route, retains its original captured head while allowing actual
head advancement, and renews source authority before and after each page.
Upstream coverage stays UNKNOWN, event-time provenance UNQUALIFIED and negative-
evidence/RULE readiness false. These are retained journal prefixes, not sealed
snapshots, source completeness or autonomous evaluator grants.

The V019 generation increment assigns a separate durable input-generation UUID
inside the actual observation transaction, not from a schema snapshot or process
memory. Existing stream heads define an explicit generation-start checkpoint/time;
historical generation-less facts and completed receipts remain unchanged. Only
new accepted observations after that cutover carry the new versioned profile and
generation identity in `accepted-boolean-text-2`. Reads and schema publication never
mint generations. The fixed internal telemetry owner read reports actual persisted
state; the signed source capture description renews source/account state before
and after that RPC. Readiness distinguishes missing schemas/input/pending work and
a ready journal without asserting upstream completeness, clock qualification,
negative evidence or evaluator readiness. The compiler adapter permits head
progress but rejects rollback or generation/cutover/source pin drift. Capture
remains explicitly selected and disabled
by default; a generation is not a continuity/coverage certificate.

Preserve canonical resource IDs, signed access, existing arithmetic AST v1, current circle endpoints and saved reviewed plans. New schemas/operators must be versioned and advertised only when implemented. Preview and runtime use the same canonical contracts. Require independent cross-domain, negative, replay and access tests; a model explanation is not evidence of completion. Keep implementation tasks unchecked until code and verification exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.

## Bus expressibility/readiness audit

The [bus readiness contract](../../../docs/target/configurable-operations-bus-readiness.md) adds BUS01-BUS10 capture, occurrence routing, exact door predicate and exit/non-arrival gates to this pending work. It preserves generic core ownership, required-stops-only user policy and all existing frozen fixtures/quality gates; it is not evidence of implementation.
