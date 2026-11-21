# Design: multiprotocol event transport

## Baseline And Boundary

Current structure is described in
[telemetry ingestion](../../../docs/current/architecture/telemetry-ingestion.md).
Code inspection on 2026-11-19 found `spring-boot-starter-webmvc`, Paho
`MqttClient`, JDK HTTP polling, DigitalPetri Modbus and Eclipse Milo OPC UA
dependencies. Registry injection of a collection of adapters is lookup/wiring;
a singleton adapter does not by itself serialize all messages.
`ConnectorRuntime` schedules supervisor work; serial worker traversal can delay
other sources. `TelemetryEventPublisher.publish` waits on `send(...).get(timeout)`.
The pipeline also has external source recovery, shared correctness and usage I/O.
These operations cannot move to event loops unchanged.

The current architecture text says source cache misses fail closed until replay;
`SourceResolver` also has a `SourceRegistryLookup` recovery path in code. This
proposal preserves bounded fail-closed recovery and does not describe that RPC
as absent. Reconcile the current document against code during implementation.

## Decisions

1. Use a Spring-managed Netty transport runtime for listeners and client channels,
   with explicit endpoint/protocol/security profiles. Do not require one port or
   one global EventLoopGroup. Keep SDK-owned groups when validated ownership
   requires them; shared groups are an optimization with a compatibility gate.
2. Decode byte framing/session state in channel-affine protocol handlers; map
   complete immutable bounded protocol messages through capability-specific
   registry adapters into existing `IncomingTelemetryMessage`.
3. Introduce an application execution/completion port around the current core.
   Reserve bounded bytes/items/in-flight capacity before work; isolate heavy CPU
   mapping and blocking I/O in separate bounded executors. Initially the entire
   synchronous core may run on a bounded worker, including its Kafka wait.
   Async clients may replace blocking implementations only with contract tests.
4. Preserve Kafka-confirmed success in publishing deployments. An admission token
   is not a durable receipt. Cancellation, timeouts and duplicate reservations
   need explicit pending/confirmed/unknown handling; never upgrade a pending
   idempotency reservation into proof of a previously published event.
5. Preserve connector ownership and fencing through queued work and completion.
   Do not replace ownership with a Netty channel or a local scheduler.
6. Deliver each mode behind explicit opt-in routing and ownership generations.
   Full target coverage includes HTTP, MQTT, HTTP polling, Modbus TCP and OPC UA;
   staged legacy SDK isolation is visibly incomplete until qualification.

The normative [target contract](../../../docs/target/ingestion-event-transport.md)
defines admission, ACKs, ordering, SDK gates, rollout and acceptance IDs ET01–ET12.
The [proposed ADR](../../../docs/adr/ADR-016-netty-multiprotocol-ingestion.md)
records tradeoffs. New generic TCP/vendor protocols require their own protocol
contract; this change does not invent a wire format or implement an MQTT broker.

## Delivery Gates

| Gate | Deliverable and exit condition |
| --- | --- |
| G0 | Freeze current contracts, protocol/SDK compatibility matrix and reproducible baseline/target workload; no capacity promise before these are recorded |
| G1 | Bounded execution/completion port, ownership/ordering/context tests and isolated blocking pipeline; current modes still available |
| G2 | Netty HTTP telemetry listeners and HTTP polling client, endpoint/TLS validation and single/batch/import parity |
| G3 | Netty-backed external-broker MQTT client, proven ACK/session/reconnect semantics, stable identities and replica subscription ownership |
| G4 | Qualified Modbus and OPC UA transport integration or reviewed client replacement; preserve industrial security/session/mapping and leases |
| G5 | Combined correctness, resource, overload, recovery, scaling and rollback qualification across all modes; only then consider default enablement |

G2–G4 may be developed separately after G1, but full completion requires all modes.
If an SDK cannot satisfy ACK, lifecycle, security or Netty compatibility gates,
its mode remains on the explicitly isolated existing path and its gate is BLOCKED;
the change cannot be called a completed common Netty migration. A future scope
amendment must explicitly change coverage rather than relabel a wrapper as Netty.

## Alternatives And Risks

- HTTP-only WebFlux/Reactor Netty: valid HTTP tool choice, insufficient scope.
- One listener with packet sniffing: no general reliable framing or protocol
  negotiation; explicit endpoints are simpler and auditable.
- Handwritten MQTT/OPC UA stacks: large session/security correctness burden;
  prefer qualified mature clients over codecs alone.
- Unlimited queues or virtual threads: neither bounds memory or downstream work.
- A universal shared loop group: SDK ownership/version coupling can break shutdown
  or isolation; allow qualified SDK-owned Netty groups with visible resource caps.

Risks are direct-memory leaks, queue amplification, starvation, retry storms,
ambiguous publisher timeouts and industrial session regressions. Acceptance tests
must expose each. Netty reduces thread-per-connection overhead where applicable;
it does not automatically accelerate CPU parsing or resolve downstream capacity.

## Open Decisions With Owners

The future ingestion implementer records exact BOM-managed Netty/SDK versions,
client APIs and group ownership at G0. The product/operations owner freezes
numerical workload/SLO/resource budgets before comparative runs. MQTT operations
select broker-supported subscription/session topology and application retry
contract. Security operations choose TLS termination and industrial trust policies.
These are explicit implementation gates; none prevents this documentation change.
