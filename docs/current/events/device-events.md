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
