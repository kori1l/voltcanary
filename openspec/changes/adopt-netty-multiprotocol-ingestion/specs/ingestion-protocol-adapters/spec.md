## ADDED Requirements

### Requirement: Every Existing Protocol Has A Qualified Netty Migration Path

The transport migration SHALL cover HTTP push/import, external-broker MQTT,
HTTP polling, Modbus TCP and OPC UA. Exact SDK/version/transport, loop ownership,
ACK/cancellation/security and lifecycle capabilities SHALL be verified before
that mode is enabled. Isolated legacy clients SHALL NOT count as completed Netty migration.

#### Scenario: An industrial SDK cannot share the application loop group

- **WHEN** the selected Modbus or OPC UA SDK lacks a supported group injection API
- **THEN** the implementation SHALL qualify SDK-owned Netty groups or a client replacement
- **AND** SHALL preserve supported mapping/session/security and ownership semantics
- **AND** an unqualified mode SHALL remain explicitly incomplete rather than be silently omitted.

### Requirement: MQTT Preserves Broker Session And Application Completion Boundaries

MQTT SHALL retain the external broker and use a qualified Netty-backed client,
not a codec alone. Broker-hop ACK, subscriber QoS/session acknowledgement,
terminal rejection and successful pipeline publication SHALL be distinguished.

#### Scenario: Subscriber capacity is exhausted

- **WHEN** a QoS1/2 message cannot enter bounded processing
- **THEN** the client SHALL use qualified flow control or disconnect/redelivery policy without falsely acknowledging successful ingestion
- **AND** session persistence/manual ACK behavior SHALL be peer-tested at the required completion boundary.

#### Scenario: QoS0 or subscription overflow loses input

- **WHEN** delivery cannot be recovered through the qualified protocol contract
- **THEN** ingestion SHALL expose the loss/gap and UNKNOWN completeness
- **AND** SHALL NOT claim reliable delivery or full capture from reconnect alone.

### Requirement: Active Clients Use Bounded Work Under Connector Ownership

Netty-backed polling and subscriptions SHALL retain connector scheduling, lease
generation/fencing, retries and per-source health. SDK notification queues and
due polling work SHALL have finite limits and observable defer/gap policies.

#### Scenario: One endpoint is slow while other sources are due

- **WHEN** HTTP polling, Modbus or OPC UA work reaches its configured deadline or quota
- **THEN** the source SHALL back off, defer or expose a gap per its contract
- **AND** SHALL NOT block lease renewal/control capacity or unrelated source progress
- **AND** stale queued callbacks SHALL NOT publish after ownership/config invalidation.
