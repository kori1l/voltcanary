# Tasks: Replicate Ingestion Source Runtime Config

## 1. Source Runtime Event

- [x] 1.1 Add `source.runtime-config.v1` event model with source identity, adapter/protocol/transport, status, mapping, region, version, and credential references.
- [x] 1.2 Publish source runtime config changes from device-service through the outbox.
- [x] 1.3 Document topic key, value schema, tombstone/disable behavior, and versioning.

## 2. Ingestion Read Model

- [x] 2.1 Add ingestion consumer for `source.runtime-config.v1`.
- [x] 2.2 Add local repository/cache rebuild behavior for startup and ongoing updates.
- [x] 2.3 Replace telemetry hot-path source resolution with local read-model reads.
- [x] 2.4 Remove or explicitly localize synchronous device-service fallback behavior.

## 3. Documentation And Verification

- [x] 3.1 Update ingestion API/event docs and target architecture links.
- [x] 3.2 Add source runtime config publisher, consumer, and resolver tests.
- [x] 3.3 Run `cd backend; mvn -pl services/device-service,services/ingestion-service -am test`.
- [x] 3.4 Run `npm run openspec:validate`.
