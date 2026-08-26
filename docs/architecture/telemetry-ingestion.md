# Telemetry Ingestion

`ingestion-service` is the unified telemetry boundary. HTTP requests and MQTT
messages must map through an adapter boundary to canonical
`IncomingTelemetryMessage` and call `TelemetryIngestionPipeline`.

The canonical ingress contract carries VoltCanary semantics: `sourceId`,
optional `deviceId` and `accountId`, `occurredAt`, `receivedAt`, metrics,
`TelemetryOrigin`, `IngestionContext`, and import mode. `TelemetryOrigin`
records adapter type, protocol type, transport type, adapter ID, and bounded
metadata such as an MQTT topic when needed for observability. Protocol-specific
objects, raw protocol payloads, MQTT topic parsing, Modbus register maps, OPC UA
node IDs, and vendor response bodies stay in concrete adapter packages.

Adapter contracts live under `backend/services/ingestion-service/src/main/java/com/voltcanary/ingestion/adapter/api`.
They define adapter type, protocol type, transport type, capabilities,
manifests, source runtime configuration, inbound mapping, polling,
subscription, registry, resolver, and dispatcher contracts. The common
ingestion core may depend on canonical models and adapter API contracts, but it
must not depend on concrete protocol adapter packages.

HTTP ingestion now uses the adapter route:

```text
IngestionController
  -> IngressOrchestrator
  -> HttpTelemetryAdapter
  -> IncomingTelemetryMessage
  -> TelemetryIngestionPipeline
```

`IngestionController` owns HTTP routes, DTO validation, bearer-token extraction,
correlation headers, client IP extraction, and response status mapping.
`HttpTelemetryAdapter` owns DTO-to-canonical mapping for realtime, batch, and
historical import requests. `IngressOrchestrator` dispatches inbound adapter
requests to the common pipeline, keeps batch size accounting, and preserves
per-item partial-success semantics.

MQTT ingestion uses the same canonical boundary:

```text
MqttIngress
  -> MqttTelemetryAdapter
  -> MqttTopicResolver
  -> MqttPayloadMapper
  -> IncomingTelemetryMessage
  -> TelemetryIngestionPipeline
```

`MqttIngress` owns broker connection lifecycle, subscription callbacks, and
adapter-scoped MQTT metrics. `MqttTelemetryAdapter` owns topic resolution and
payload mapping. Unsupported topics and invalid payloads are rejected before the
common pipeline sees a message, so pipeline code does not branch on MQTT topic
syntax or raw MQTT payload shape.

Modbus TCP ingestion is an active connector adapter and runs only through
connector runtime ownership:

```text
ConnectorRuntime
  -> ConnectorSupervisor lease ownership
  -> ModbusTcpTelemetryAdapter
  -> Modbus register mapping
  -> IncomingTelemetryMessage
  -> TelemetryIngestionPipeline
```

Source runtime config for `GENERIC_MODBUS_TCP` uses `protocol=MODBUS_TCP`,
`transport=TCP`, `adapterConfigSchemaVersion=v1`, and `mappingSchemaVersion=v1`.
The adapter config contains endpoint and lifecycle fields:

```json
{
  "host": "plc-1.internal",
  "port": 502,
  "unitId": 1,
  "pollingInterval": "5s",
  "timeout": "2s",
  "timestampPolicy": "POLL_TIME"
}
```

The mapping contains versioned register-to-metric entries:

```json
{
  "registers": [
    {
      "metric": "voltageV",
      "function": "HOLDING_REGISTER",
      "address": 0,
      "dataType": "UINT16",
      "scale": 0.1,
      "offset": 0,
      "unit": "V",
      "byteOrder": "BIG_ENDIAN",
      "wordOrder": "BIG_ENDIAN"
    }
  ]
}
```

Supported Modbus register functions are `HOLDING_REGISTER` and
`INPUT_REGISTER`. Supported data types are `INT16`, `UINT16`, `INT32`,
`UINT32`, and `FLOAT32`. Register addresses, function codes, unit IDs, and byte
ordering stay inside `adapter.modbus`; the common pipeline receives only
canonical metric names and values. Invalid mappings are rejected before polling.
Connection failures, timeouts, endpoint errors, lease loss, and repeated
mapping failures are reported through connector runtime per-source health and
use connector backoff.

The pipeline validates required fields, timestamp skew/backfill limits,
supported metric names, metric value ranges, source and metric name lengths,
and batch size. It resolves source identity, account, device, adapter,
protocol, transport, status, mapping metadata, and token hash from the local
`source.runtime-config.v1` read model. It verifies that the submitted token
belongs to the submitted `sourceId` and `deviceId`, checks source
status/protocol, applies idempotency, builds `TelemetryReceivedEvent`, waits for
a broker acknowledgement, and publishes to `telemetry.raw.v1`. Normal telemetry
validation does not synchronously call `device-service`; missing local source
state fails closed until the compacted runtime-config stream has populated the
read model.

Telemetry-service repeats defensive validation before persistence. It treats ingestion as the primary external trust boundary, but still rejects malformed events, unsupported metrics, out-of-window timestamps, and out-of-range values before writing TimescaleDB rows.

Batch ingestion uses partial-success semantics. The response includes accepted, duplicate, and rejected counts plus per-reading status entries. Kafka publish failures return `503`; the idempotency key is released so collectors can retry the same reading.

Realtime ingestion enforces rate limits by source, device, account, and client
IP. In multi-replica mode, idempotency and rate limiting must both use Redis.
Startup fails when multi-replica mode is configured without Redis-backed
idempotency or rate limiting, and Redis request-time outages fail closed with
`503` instead of silently degrading to local memory. The local in-memory
implementations are only valid when explicitly configured as single-instance
local correctness state.

When Kafka publish failures occur, the service opens a short backpressure
window and returns `503` before accepting more readings. The Kafka key for
`telemetry.raw.v1` is always `deviceId`. The MVP keeps operational history in
bounded in-memory audit logs and Micrometer metrics; it does not store telemetry
time-series data in ingestion.

Historical imports use `/ingestion/imports/readings` and
`/ingestion/imports/readings/batch`. Import endpoints keep the same validation
and source-token checks but allow older timestamps than realtime ingestion.
