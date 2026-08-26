# Tasks: Align Ingestion Target Architecture

## 1. Inbound Adapter Dispatch

- [x] 1.1 Add a registry-backed inbound dispatcher for `InboundTelemetryAdapter`
  resolution by adapter type, protocol, transport, and inbound capability.
- [x] 1.2 Route HTTP single, batch, and import ingestion through the inbound
  dispatcher without bypassing per-message validation, idempotency, rate limit,
  or Kafka publish behavior.
- [x] 1.3 Route MQTT transport messages through the same inbound dispatcher
  while keeping topic and payload parsing inside the MQTT adapter package.
- [x] 1.4 Add tests proving a new inbound adapter can be registered without
  changing `DefaultTelemetryIngestionPipeline`.

## 2. Control-Plane Connector Source Config

- [x] 2.1 Add first-class `MODBUS_TCP` and `OPC_UA` source protocols to
  `device-service` source management.
- [x] 2.2 Publish `source.runtime-config.v1` events for Modbus TCP and OPC UA
  sources with the expected adapter type, protocol, transport, region, mapping,
  adapter config, version, and credential references.
- [x] 2.3 Persist or model the minimal structured source mapping and adapter
  configuration required to create those runtime config events.
- [x] 2.4 Add publisher and ingestion consumer tests that prove Modbus TCP and
  OPC UA source config reaches the local ingestion read model without raw
  secrets.

## 3. Connector Lease Fencing

- [x] 3.1 Extend connector lease ownership with a fencing token or generation
  that changes on new ownership.
- [x] 3.2 Ensure renew and release operations only apply to the current owner
  and fencing generation.
- [x] 3.3 Add tests proving a stale owner cannot publish or continue
  subscription work after failover.
- [x] 3.4 Document that command-capable adapters require fenced ownership before
  they can be enabled.

## 4. Documentation And Verification

- [x] 4.1 Update ingestion API, event, operation, and architecture docs to
  describe the final one-service ingestion baseline.
- [x] 4.2 Check whether `AGENTS.md` or project skills need updates because the
  ingestion baseline changed.
- [x] 4.3 Run `cd backend; mvn -pl common/common-events,services/device-service,services/ingestion-service -am test`.
- [x] 4.4 Run `npm run openspec:validate`.
