# Device Events

Device-service publishes registry and source configuration events through the
device outbox. These are current produced topics backed by service code and
common event records.

## `device-registry.changed.v1`

Producer: `device-service`.

Current consumers: none in this repository. The topic is emitted for audit,
external projections, and downstream registry integrations, but no current
runtime flow depends on consuming it.

Kafka key:

```text
accountId when account ordering is available, otherwise resourceId
```

Record:

```text
com.voltcanary.common.events.device.DeviceRegistryChangedEvent
```

Important fields:

- `eventId`
- `eventVersion`
- `resourceType`
- `action`
- `accountId`
- `deviceId`
- `resourceId`
- `status`
- `occurredAt`
- `correlationId`
- `producer`

Current resource/action producers include:

| Resource type | Actions |
| --- | --- |
| `DEVICE` | `CREATED`, `UPDATED`, `STATUS_CHANGED` |
| `LOCATION` | `CREATED`, `UPDATED`, `DELETED` |
| `SENSOR` | `CREATED`, `UPDATED`, `DELETED` |
| `DATA_SOURCE` | `CREATED`, `UPDATED`, `DISABLED`, `TOKEN_ROTATED`, `TOKEN_REVOKED` |
| `ANALYTICS_CONFIG` | `UPDATED` |

Account deletion cleanup also publishes `DEVICE` status changes and
`DATA_SOURCE` disable/revoke changes for affected account resources.

## `source.runtime-config.v1`

Producer: `device-service`.

Consumer: `ingestion-service`.

Kafka key:

```text
dataSourceId
```

Topic policy: compacted.

Record:

```text
com.voltcanary.common.events.device.SourceRuntimeConfigEvent
```

The event carries source identity, account/device ownership, adapter selection,
source status, versioned mapping/config payloads, credential references,
optional token prefix/hash, home region, and monotonic `configVersion`.

A confirmed source conversion is published under
`mapping.reviewedBooleanMappings` with the same runtime configuration and
`bindingSchemaPin`. Ingestion applies the token conversion before emitting the
typed observation. Replaying an accepted event uses its original normalized
values and schema pin.

Current protocol mapping from device-service:

| Data-source protocol | Adapter type | Protocol | Transport |
| --- | --- | --- | --- |
| `REST` | `GENERIC_HTTP_PUSH` | `REST` | `HTTP` |
| `MQTT` | `GENERIC_MQTT` | `MQTT` | `MQTT` |
| `CSV` | `CSV_IMPORT` | `CSV` | `FILE` |
| `HTTP_POLLING` | `GENERIC_HTTP_POLLING` | `HTTP` | `HTTP` |
| `MODBUS_TCP` | `GENERIC_MODBUS_TCP` | `MODBUS_TCP` | `TCP` |
| `OPC_UA` | `GENERIC_OPC_UA` | `OPC_UA` | `TCP` |
| `WEBHOOK` | `WEBHOOK` | `WEBHOOK` | `HTTP` |
| `HOME_ASSISTANT` | `VENDOR` | `HTTP` | `HTTP` |
| `VENDOR_API` | `VENDOR` | `HTTP` | `HTTP` |

Compatibility rules:

- Higher `configVersion` values replace lower versions for the same
  `dataSourceId`.
- Older events are ignored by ingestion replicas.
- `DISABLED` and `REVOKED` statuses stop local ingestion use.
- Credential fields carry references only, not raw credentials or raw tokens.

## `source.binding-schema.v1`

Producer: device-service, from the immutable source schema journal and existing
device outbox. Consumer: the explicitly enabled telemetry observation schema
replica. Wire shape is `EventEnvelope<SourceBindingSchemaEvent.Payload>` with
eventType `SourceBindingSchemaPublished`, eventVersion1, producer `device-service`,
accountId and an event-ID correlation fallback.

Payload is `{pin,canonicalDefinition,sourceActive}`. The closed schema pin is
`{bindingId,accountId,deviceId,externalSourceId,schemaRevision,snapshotId,
definitionDigest}`. The definition is exact canonical Boolean/TEXT typing metadata;
unsupported legacy numeric fields retain their conversion gaps. Raw adapter
configuration, credentials, token hashes and resource permissions are excluded.
The consumer verifies canonical bytes/digest, schema identity and immutable pins.
Conflicting revisions hold delivery rather than silently replacing old metadata.

Kafka key is `bindingId:schemaRevision`. The compacted topic retains each schema
revision separately; compacting only by binding ID would lose pinned historical
schemas. Source activity is advisory publication state, not fresh owner authority.
The topic is provisioned by Compose and Kubernetes bootstrap. Its outbox write
commits with ordinary source runtime publication; rollback publishes neither.

The runtime mapping and internal source response contain server-owned
`bindingSchemaPin`. Authored values under that key are removed before the real
journal pin is inserted. A legacy source without a journal gets no synthetic pin;
stale/corrupt journals fail closed and reads never repair them. Ingestion validates
the pin against its resolved account/device/source and carries it independently
of incoming metrics/context. A schema pin is neither capture input generation,
current ACL, coverage certificate nor RULE execution readiness.
