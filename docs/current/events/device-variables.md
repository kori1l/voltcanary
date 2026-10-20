# Device variable events

| Topic | Producer | Consumers | Key |
| --- | --- | --- | --- |
| `source.processing-config.v1` | device transactional outbox | ingestion replicas, telemetry | source UUID |
| `telemetry.measurements.accepted.v1` | telemetry transactional outbox | analytics variable rules | device UUID |
| `source.processing-config.dlq.v1` | processing consumers | operations | original key |
| `analytics.variable-measurements.dlq.v1` | analytics consumer | operations | original key |

Processing snapshots contain eventId, version, accountId, deviceId, dataSourceId,
sourceId, monotonic generation, digest, enabled, bindings, occurredAt and producer.
Each binding contains the immutable numeric contract and retained-input mapping.
The compacted topic accelerates current projection; authenticated paged snapshots
recover historical generations and cold starts. Conflicting content for the same
generation/digest is rejected. Replays are idempotent and older generations never
replace current state.

`telemetry.raw.v1` adds optional trusted `processingContext` with source UUID,
generation, digest, inputSchemaVersion and historicalImport. Legacy payloads
without this field retain the previous path. Ingestion stamps this context after
source authentication; callers cannot submit it as telemetry metadata.

Accepted events contain eventId, version, account/device/source identity,
sourceEventId, timestamp, processingContext, eligibility (`LIVE` or `BACKFILL`),
bounded measurements and producer. Measurements contain variableId, revision,
key, origin, value, unit, quality and lineageId. Only valid outputs are accepted;
failures remain durable lineage. A maximum of 32 outputs is allowed per bundle.
Telemetry receipt and accepted outbox commit together. Replay cannot publish an
additional logical accepted event for the same source event.

The existing `anomaly.detected.v1` event adds optional `variableEvidence` carrying
source/variable/lineage identity, original event, rule revision, observed value,
unit, freshness, cooldown and dedup policy. Old producers/readers remain valid.
The analytics consumer has a shared durable group and persists per-event/rule
evaluation identity and source-specific cooldown state. Historical-import values
never trigger live variable alerts. Chart reductions are never alert inputs.

Legacy validated-event reconciliation excludes configured variable lineage and
selects only registered built-in metrics. It cannot reclassify a fresh explicit
import or introduce computed storage tokens into the legacy analytics path.

Invalid processing/accepted payloads retry twice then enter their DLQ.
Infrastructure errors retry with backoff without acknowledging the record.
Unknown referenced telemetry bundles enter durable pending storage (10,000-entry
bound), retried in batches and eventually sent through the raw telemetry DLQ.
Do not delete historical snapshots while parked events still reference them.
