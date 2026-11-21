# Change: Netty multiprotocol event transport for ingestion

Status: proposed, documentation only, 2026-11-19. Implementation, runtime changes,
load campaigns, commits and deployment are outside this request.

## Why

VoltCanary needs an explicit connection-oriented event transport for many IoT
devices sending short events across protocols. The current MVC controller,
Paho callback and connector workers enter a synchronous pipeline through separate
execution models. Replacing HTTP MVC with WebFlux alone would leave MQTT,
polling, Modbus and OPC UA lifecycle, blocking work and overload unresolved.
No connection count, event-rate target or numeric SLO has been agreed or qualified.

## What Changes

- Separate Netty transport, protocol framing/session handling, registry-selected
  domain adapters and the protocol-neutral ingestion core.
- Introduce bounded asynchronous admission/completion, per-tenant fairness,
  explicit ordering, buffer ownership and shutdown contracts.
- Migrate HTTP push/import, MQTT broker consumption, HTTP polling, Modbus TCP
  and OPC UA in gated stages. Require an SDK compatibility decision for every
  mode; an executor wrapper is isolation, not Netty migration.
- Preserve authorization, runtime config, mapping/processing/schema pins,
  source positions, idempotency and batch/import semantics.
- Qualify comparable workloads and all ingress modes before default enablement;
  document opt-in cutover and rollback without duplicate active paths.

## Scope And Dependencies

Owns ingestion transport and its application execution boundary only. Spring
remains responsible for DI, configuration and lifecycle. The existing external
MQTT broker, `telemetry.raw.v1`/`deviceId` key, Kafka configuration and downstream
telemetry/analytics/ML/alert service boundaries remain in place. Kafka tuning,
partition/broker redesign and a new durable ingestion spool are separate work.
Isolating the existing blocking publisher is required here for event-loop safety.

`npm run openspec:list` found no active transport change. Reuse the permanent
adapter/runtime-config/scaling specs. Coordinate with
[`configure-quality-aware-event-streams`](../configure-quality-aware-event-streams/proposal.md)
for mapping, source positions and capture quality, and
[`package-source-mappings-and-solution-templates`](../package-source-mappings-and-solution-templates/proposal.md)
for mapping configuration; do not take over either owner's work. The superseded
`preserve-vehicle-operational-events` is scenario context, not an implementation
dependency. Current HTTP/industrial features may not be silently dropped while
migrating. Compatibility decisions use the then-current checked-out contracts.

## Artifacts And Impact

The [design](design.md) owns architectural choices and staged delivery;
[target contract](../../../docs/target/ingestion-event-transport.md) owns detailed
execution, protocol and qualification rules; [ADR-016](../../../docs/adr/ADR-016-netty-multiprotocol-ingestion.md)
records the proposed durable decision. Deltas extend ingestion architecture,
protocol adapters and scaling, and add `ingestion-event-transport`.
Implementation will affect ingestion-service wiring, transport/protocol adapters,
connector scheduling, tests and opt-in deployment configuration. No code or
current runtime API/event behavior changes in this proposal.

Documentation checks and evidence limits: [verification report](verification.md).
