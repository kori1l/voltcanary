# Telemetry Events

Telemetry event types and schemas.

## `telemetry.raw.v1`

Producer: `ingestion-service`.

Key: `deviceId` as a string. The key is deterministic so all raw telemetry for
one device is ordered by the same Kafka partition.

Consumers should treat delivery as at-least-once and use event ids or derived
idempotency keys before side effects.

Record:

```text
com.voltcanary.common.events.telemetry.TelemetryReceivedEvent
```

Important fields:

- `eventId`
- `deviceId`
- `sourceId`
- `timestamp`
- `metrics`
- `receivedAt`
- `eventVersion`
- `correlationId`
- `producer`
- `accountId`
- `occurredAt`

Raw events also have optional `processingContext` for the existing numeric AST
pipeline and optional `bindingSchemaPin` for source-owned schema identity. Old
constructors/wire messages leave either extension null. Only the resolved source
owner mapping supplies the schema pin; caller metric/context JSON cannot assign it.
Its closed fields and publication topic are in [device-events.md](device-events.md#sourcebinding-schemav1).

## `telemetry.observations.accepted.v1`

Producer: telemetry-service. The current consumer boundary is the durable event
contract; configurable temporal runtime consumption remains pending. Kafka key is
bindingId. Wire shape is `EventEnvelope` with eventType `ObservationAccepted`,
version1 and the original input eventId, account and event time. Delivery is
at-least-once; consumers deduplicate by input event identity, never timestamp.

The opt-in `accepted-boolean-text-2` profile persists one complete observation
payload per accepted input in V018/V019, separately from sampled/latest measurements.
Payload includes `inputEventId,bindingSchemaPin,eventTime,receivedAt,recordedAt,
checkpoint,inputGeneration,fields,upstreamCoverage,eventTimeProvenance,negativeEvidenceReady,
ruleExecutionReady`. Checkpoint is a positive decimal string local to that binding.
Fields carry `{fieldId,type,knowledge,value,reason}`. Boolean/TEXT values use exact
source field identity and types; false and empty text remain KNOWN. An unavailable
value is UNKNOWN with `MISSING_OR_NULL_INPUT`, without claiming that this legacy
adapter can distinguish original null from absence. Numeric conversion is unsupported.

Upstream coverage is UNKNOWN, event-time provenance UNQUALIFIED and both readiness
flags false. Preserving accepted events proves neither upstream completeness nor
door-transition/absence coverage. Schema snapshots are not capture generations.
This profile cannot qualify RULE activation, intervals or negative temporal facts.

V019 adds a separately generated, durable input-generation UUID. It is assigned
inside the first actual new observation transaction under the existing device/
stream fences, with `generation_start_checkpoint` equal to the previous committed
head and a fresh database `generation_started_at`. Generation, first fact, receipt,
checkpoint and outbox roll back together. Rebuilt capture components reload the
same generation from SQL; schema publication and reads never mint it. The UUID is
not the binding/schema snapshot identity. The database also guards binding/generation
association for every generation-bearing fact.

Existing `accepted-boolean-text-1` facts retain their original payloads, receipts
and null generation column. Migration never invents generation for old facts or
re-emits them. New profile2 input starts strictly after the explicit checkpoint
cutover. Generation does not certify uninterrupted upstream delivery across
disable/restart periods; upstream coverage remains UNKNOWN. Reviewed generation
replacement/capture controls remain pending.

Input digest uses the serialized raw event as text, preserving unsafe legacy
numeric representations without JCS number aliasing. Identical replay retains the
fact, checkpoint and receipt; changed serialized input under the same eventId is
a conflict. Representational changes are conservatively conflicts. Observation,
input receipt, per-binding checkpoint and outbox commit in one local transaction.
Distinct accepted event IDs at the same timestamp survive legacy latest coalescing.

Missing schemas are persisted as PENDING against the exact pin, without replacing
them with the newest revision. Recovery pages20 rows and delays held work fairly;
it uses stored input bytes and emits once after the exact schema arrives. Raw
capture failures and quota limits hold Kafka delivery for recovery/intervention;
schema corruption holds its schema partition. Device deletion uses the existing
device fence, purges observation/pending/schema/outbox data and prevents resurrection.

Capture is disabled by default. `OBSERVATION_CAPTURE_ENABLED=true` plus explicit
`OBSERVATION_CAPTURE_BINDING_IDS` (at most64 UUIDs) selects bindings. Defaults are
10000 pending inputs and100000 observations per binding, at most1000 retained
schema revisions, raw pending payload at most1MiB. Bounds hold work rather than
evicting evidence. No automatic retention/compaction of accepted facts is implemented;
capacity/performance and user-reviewed capture authoring remain pending. Changing
the configured binding selection derives a new schema-consumer group so its retained
schema history is replayed from earliest, instead of reusing already-advanced offsets.

## Observation readiness owner read

`POST /internal/observations/binding-readiness` is a fixed read-only telemetry owner
RPC. It requires `X-Internal-Service-Token` even when legacy authorization is
disabled. Its exact body is the seven-field source-owned schema pin documented
above. Caller JSON cannot supply generation, readiness, actor privileges or coverage.
The typed response carries pin/profile/state, nullable inputGeneration/cutover/head/
start time and conservative quality flags. It contains no observation values.

The owner reads a consistent SQL snapshot and verifies persisted schema identity,
account/device association, local deletion fence, configured selection, pending
inputs and real generation. States are DISABLED, SCHEMA_PENDING, AWAITING_INPUT,
INPUT_PENDING and READY. `journalReady` is true only for READY with actual new
generation-bearing input and no pending input. Pending work removes readiness
without replacing generation or cutover. Negative-evidence and RULE readiness stay
false; coverage UNKNOWN, clock UNQUALIFIED and maximumGapUs null. This is local
journal metadata, not evaluator/delivery readiness or a sealed row read snapshot.

device-service exposes it through the freshly signed/source-authorized
[capture description](../api/configurable-native-data.md#source-capture-description),
renewing source/account state after the RPC. Owner failure is not cached as usable
capture. The base source schema describe API remains SQL-only and conservative.

`POST /internal/observations/read` reads a bounded contiguous accepted-checkpoint
prefix under an exact schema/generation and caller-selected retained cut. The signed
source owner renews source/account authority before and after this RPC. Equal-time
facts remain separate; reaching the cut proves only that local prefix. Coverage
stays UNKNOWN and negative-evidence/RULE readiness remain false. See the
[accepted observation read contract](../api/configurable-native-data.md#accepted-observation-prefix-reads)
for bounds and failure behavior.

V020 adds a canonical-payload checksum to newly accepted journal rows in the same
transaction. Reads verify it; old rows retain their original bytes and null
checksum and are explicitly unavailable to this integrity-checked read profile.
Replay never backfills a checksum. This storage change does not alter the existing
accepted event profile or Kafka topic.

## `telemetry.validated.v1`

Producer: `telemetry-service`.

Consumer: `analytics-service`.

Key: `deviceId` as a string.

Record:

```text
com.voltcanary.common.events.telemetry.TelemetryValidatedEvent
```

Telemetry-service publishes validated events for accepted realtime raw
telemetry. Historical/backfill events are persisted with quality flags but are
not emitted as realtime validated events.

Important fields:

- `eventId`
- `deviceId`
- `sourceId`
- `timestamp`
- `metrics`
- `qualityFlags`
- `validatedAt`
- `eventVersion`
- `correlationId`
- `producer`
- `accountId`
- `occurredAt`

## `telemetry.raw.dlq.v1`

Producer: `telemetry-service`.

Consumer: none in this repository.

Key: `deviceId` when available, otherwise the generated dead-letter id.

Record:

```text
com.voltcanary.telemetry.event.TelemetryDeadLetterEvent
```

Telemetry-service writes malformed, invalid, rate-limited, or otherwise
unprocessable raw telemetry to this topic through its telemetry outbox. The
payload includes the original event object when available, a reason code,
quality flags, and `failedAt`.

## Source Runtime Config

For authenticated live sources, `telemetry.raw.v1` may carry a producer
`sourcePosition` with an epoch UUID and exact decimal sequence. The position
is scoped to the server-resolved `bindingSchemaPin`. Unpositioned events
preserve the original wire shape. Telemetry records position and observation
atomically. Position continuity is distinct from qualified event-time
coverage.

Source runtime configuration is documented with device events:
[device-events.md](device-events.md#sourceruntime-configv1).

## Additive geographic readings

Current telemetry.raw.v1/validated metrics maps may contain paired latitude and
longitude (degrees), speedKmh and headingDeg. Simulator sends through ingestion;
telemetry validates and stores one accepted atomic position per source/timestamp.
No new topic/envelope version. Duplicates retain the first accepted point; accepted
late/backfill points remain source-specific. GPS is not automatically selected
for energy/ML features. Deploy compatible ingestion/telemetry catalogs before
enabling VEHICLE_GPS.

## Vehicle readings extension

GPS telemetry.received v1 may carry optional numeric ignition, fuelPercent, odometerKm in metrics. Topic/envelope/source/timestamp identity remains unchanged; vehicle values and coordinates project atomically after acceptance.


## Observed transport context

GPS telemetry may include metrics.transport: a bounded object with routeId (128 chars), routeShortName (64), direction (1/2), destination (200), nextStopId (128), scheduleDeviationSeconds (integer -86400..86400), doorsOpen (boolean), accelerationMs2 (-30..30 finite), scheduledStart (HH:mm), locationSource (GPS/ODO/MAN/DR/N/A). Unknown keys/types, control characters or unbounded values reject the context. A GPS coordinate pair is required. Context is retained in valid raw readings and accepted position snapshots, exposed as point.transport in latest/history/recordings/fleet reads, and excluded from numeric normalized measurements and telemetry.validated.v1 metrics. Older positions return null. Missing fields are not carried forward. Existing tenant/source authorization and observation timestamps are unchanged.


## Configured typed field extension

`telemetry.raw.v1` optionally contains `metrics.configuredFields`, a bounded
map of `{value,type,label,unit,display}` descriptors selected by source policy.
Ingestion filters before publishing. Telemetry validates descriptor types,
stores valid descriptors in raw readings and accepted GPS points, and projects
custom numeric fields into measurements. Existing numeric canonical metrics
remain the validated analytics payload; custom text/bool and configured numeric
fields are excluded from automatic validated analytics events. Old events and
unconfigured sources remain compatible. Source runtime events carry policy
snapshots in the existing mapping object; internal registry reads carry the
same mapping for cache reconciliation.


Configured boolean descriptors retain true/false in raw events. Telemetry storage projects them to unit boolean 0/1 state measurements for charts; they remain excluded from the automatic numeric validated analytics projection.
