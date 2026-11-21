## ADDED Requirements

### Requirement: Event Transport Is Separate From Protocol And Domain Mapping

Ingestion SHALL separate Spring-managed Netty transport, channel-affine protocol
framing/session/security, registry-selected domain adapters and the canonical core.
All supported push, import, polling and subscription modes SHALL retain the
existing IncomingTelemetryMessage and current source/schema/processing contracts.

#### Scenario: A protocol transport is migrated

- **WHEN** HTTP, MQTT or an active connector adopts Netty
- **THEN** concrete wire/SDK/Netty types SHALL stay outside canonical/core packages
- **AND** the adapter SHALL retain capability-specific dispatch and source authority
- **AND** the common pipeline SHALL preserve current validation, mapping, VCL pins, idempotency, filtered outcomes and event semantics.

#### Scenario: HTTP batch and import execution becomes concurrent

- **WHEN** individual items are offloaded asynchronously
- **THEN** historical timestamp policy and per-item outcomes/counts SHALL match the current contract
- **AND** partial results SHALL preserve input result order without treating queued items as accepted.
