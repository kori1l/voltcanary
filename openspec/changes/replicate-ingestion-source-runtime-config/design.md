# Design: Replicate Ingestion Source Runtime Config

## Event Contract

Create a compacted Kafka topic:

```text
source.runtime-config.v1
```

The value should carry the source runtime data required by the data plane:
source ID, data source ID, account ID, device ID, adapter type, protocol,
transport, source status, mapping configuration, adapter configuration schema
version, credential references, home region, and config version. Tombstones or
disabled status must remove or stop local use.

## Local Read Model

Each ingestion instance consumes the compacted topic and rebuilds local source
state after restart. The telemetry hot path reads this local model by token
prefix, external source ID, and any adapter-specific lookup key required by
registered adapters.

## Token And Credentials

Token hashes and credential references are allowed; raw credentials are not.
Adapters resolve protocol credentials through a credential provider when needed.

## Migration Policy

This project is pre-production, so the implementation may replace synchronous
source resolution directly once tests and local bootstrapping are updated. Any
temporary reconciliation task should be explicitly marked local-only or removed
before the change is complete.

## Testing

Test event publication, compaction/tombstone handling, local cache rebuild,
disabled-source behavior, token validation, and telemetry ingest while
`device-service` is unavailable.
