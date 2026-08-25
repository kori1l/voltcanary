# Ingestion Architecture

## ADDED Requirements

### Requirement: MQTT Ingestion Routes Through The Adapter Boundary

MQTT ingestion SHALL separate broker transport lifecycle from MQTT topic and
payload mapping, and SHALL emit canonical telemetry input through the adapter
boundary.

#### Scenario: MQTT telemetry arrives

- **WHEN** the MQTT ingress receives a message on a supported telemetry topic
- **THEN** MQTT-specific topic and payload mapping SHALL occur inside the MQTT
  adapter layer
- **AND** the common ingestion pipeline SHALL receive only canonical telemetry
  input plus bounded origin/context metadata.

#### Scenario: MQTT topic is unsupported

- **WHEN** the MQTT ingress receives a message on an unsupported topic
- **THEN** the message SHALL be rejected or ignored at the MQTT adapter boundary
- **AND** the common pipeline SHALL NOT need to know MQTT topic syntax.
