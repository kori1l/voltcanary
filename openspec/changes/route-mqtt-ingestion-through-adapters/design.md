# Design: Route MQTT Ingestion Through Adapters

## Flow

Target MQTT flow:

```text
MqttIngress
  -> MqttTopicResolver
  -> MqttTelemetryAdapter
  -> MqttPayloadMapper
  -> IncomingTelemetryMessage
  -> Common Ingestion Pipeline
```

`MqttIngress` owns broker connection lifecycle and subscription callbacks.
`MqttTelemetryAdapter` owns MQTT topic/payload translation into canonical
telemetry. The common pipeline only sees origin metadata such as adapter type,
protocol, and transport.

## Registration

The MQTT adapter must provide a manifest with `inbound=true`,
`transport=MQTT`, and `protocol=MQTT`. Later vendor MQTT adapters may reuse the
transport layer but should register their own adapter type.

## Failure Handling

Unsupported topics, invalid payloads, mapping failures, broker reconnects, and
pipeline rejections should be observable with adapter-specific metrics/log
fields. MQTT-specific errors should not leak into common pipeline branching.

## Testing

Keep topic parsing and payload mapping unit tests. Add adapter-level tests that
prove MQTT messages emit canonical telemetry and reject invalid input before the
pipeline.
