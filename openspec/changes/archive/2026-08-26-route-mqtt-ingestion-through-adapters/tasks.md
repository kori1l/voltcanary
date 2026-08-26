# Tasks: Route MQTT Ingestion Through Adapters

## 1. MQTT Boundary

- [x] 1.1 Split MQTT broker connection handling into `MqttIngress` or equivalent transport component.
- [x] 1.2 Add `MqttTelemetryAdapter` with manifest and inbound capability.
- [x] 1.3 Keep `MqttTopicResolver` and `MqttPayloadMapper` inside MQTT adapter packages.
- [x] 1.4 Route MQTT messages through adapter -> canonical message -> pipeline.

## 2. Observability And Failure Modes

- [x] 2.1 Add metrics/logging for unsupported topic, invalid payload, mapping failure, broker reconnect, and pipeline rejection.
- [x] 2.2 Ensure common pipeline code does not branch on MQTT topic syntax or MQTT payload shape.

## 3. Documentation And Verification

- [x] 3.1 Update MQTT collector docs if topic, payload, or failure semantics change.
- [x] 3.2 Add or update MQTT ingress and adapter tests.
- [x] 3.3 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [x] 3.4 Run `npm run openspec:validate`.
