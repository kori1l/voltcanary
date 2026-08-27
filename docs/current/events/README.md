# Event Docs

Event docs describe Kafka-compatible topics, producer/consumer ownership,
message keys, idempotency expectations, and compatibility rules.

## Start Here

| Topic | Document |
| --- | --- |
| Event envelope and common metadata policy | [event-envelope.md](event-envelope.md) |
| Kafka topic scaling, partitions, listener concurrency | [../architecture/04-event-driven-architecture.md](../architecture/04-event-driven-architecture.md) |

## Domain Events

| Domain | Document |
| --- | --- |
| Telemetry raw events and source runtime config | [telemetry-events.md](telemetry-events.md) |
| Maintenance window events | [maintenance-events.md](maintenance-events.md) |
| Analytics events | [analytics-events.md](analytics-events.md) |
| Alert events | [alert-events.md](alert-events.md) |

## Current Vs Target

Current event classes live under `backend/common/common-events`. Some topics
still publish direct event records with envelope-equivalent fields instead of
`EventEnvelope<T>` values; that transitional shape is documented in
[event-envelope.md](event-envelope.md). New cross-service topics should use an
explicit envelope unless a compatibility requirement says otherwise.

## Related Docs

- Telemetry data flow: [../architecture/03-data-flow.md](../architecture/03-data-flow.md)
- Consistency and replay: [../architecture/10-consistency-contracts.md](../architecture/10-consistency-contracts.md)
- Ingestion API: [../api/ingestion-api.md](../api/ingestion-api.md)
