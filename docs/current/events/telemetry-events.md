# Telemetry Events

Telemetry event types and schemas.

## `telemetry.raw.v1`

Producer: `ingestion-service`.

Key: `deviceId` as a string. The key is deterministic so all raw telemetry for
one device is ordered by the same Kafka partition.

Consumers should treat delivery as at-least-once and use event ids or derived
idempotency keys before side effects.

## `source.runtime-config.v1`

Producer: `device-service` source registry and horizontal ingestion smoke
fixtures.

Key: `dataSourceId` as a string.

Topic policy: compacted. Every ingestion replica consumes this topic through a
replica-specific consumer group and builds a local source read model used by
push ingress and active connector assignment.

Payload: `SourceRuntimeConfigEvent` from
`backend/common/common-events/src/main/java/com/voltcanary/common/events/device/SourceRuntimeConfigEvent.java`.

Important fields:

- `adapterType`, `protocol`, and `transport` select the ingestion adapter by
  manifest metadata. Active connector values include
  `GENERIC_HTTP_POLLING`/`HTTP`/`HTTP`,
  `GENERIC_MODBUS_TCP`/`MODBUS_TCP`/`TCP`, and
  `GENERIC_OPC_UA`/`OPC_UA`/`TCP`.
- `mapping` carries protocol-specific metric mapping such as HTTP JSON paths,
  Modbus registers, or OPC UA nodes.
- `adapterConfig` carries versioned endpoint, scheduling, and protocol
  settings.
- `credentialReferences` carries secret references only.
- `homeRegion` identifies the preferred connector region.
- `configVersion` is monotonically comparable for one `dataSourceId`.

Compatibility:

- Higher `configVersion` values replace lower versions for the same
  `dataSourceId`.
- Older events are ignored by ingestion replicas.
- Credential fields carry references only, not raw secrets.
