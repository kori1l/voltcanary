# Design: Add Modbus TCP Ingestion Adapter

## Adapter Config

Modbus TCP config should include host/port or endpoint reference, unit ID,
timeout, polling interval, register mappings, data types, scaling, units,
endianness where needed, timestamp policy, config schema version, and credential
or network access references where applicable.

## Runtime Flow

```text
ConnectorScheduler
  -> lease-owned Modbus source
  -> GenericModbusTcpAdapter.poll(sourceConfig)
  -> register values mapped to metrics
  -> IncomingTelemetryMessage
  -> Common Ingestion Pipeline
```

The adapter converts protocol-specific addressing into canonical metric names
and values. The common pipeline must not branch on Modbus register types.

## Failure Handling

Protocol operation failures use bounded retry. Repeated endpoint or mapping
failures update connector health and lifecycle backoff without failing the whole
ingestion service.

## Testing

Use a Modbus test server or library stub to cover register mapping, invalid
mapping, timeout, lease loss, retry, and successful Kafka publication.
