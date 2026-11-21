## 1. Freeze contracts and evidence (G0)

- [ ] 1.1 Inventory endpoints, current single/batch/import outcomes, MQTT QoS/client/session behavior, connector config and all canonical/schema/processing/sourcePosition contracts; reconcile source recovery docs with code.
- [ ] 1.2 Record exact dependency versions and prove Netty/client compatibility per protocol: supported APIs, event-loop ownership, threading, ACK control, cancellation, security and lifecycle; select replacement where needed.
- [ ] 1.3 Freeze baseline and target workloads, numeric acceptance budgets, hardware/topology, observability and generator/reconciliation validity under ET11–ET12 before measured comparison.

## 2. Execution boundary (G1)

- [ ] 2.1 Implement finite item/byte/in-flight admission, authenticated source/account/protocol fairness, pre-auth quotas and bounded CPU/blocking pools without caller-runs on I/O threads.
- [ ] 2.2 Add async application completion with pending/confirmed/unknown idempotency behavior, timeout/cancellation and late completion ownership; isolate current publisher wait without changing Kafka topology.
- [ ] 2.3 Preserve ordered lanes, config/processing pins, source authorization and lease fences across dispatch; propagate bounded context and release buffers/permits exactly once.
- [ ] 2.4 Verify architecture, concurrent adapters, fragmented/coalesced frames, buffer cleanup, overload, timeout races and existing contract parity together after the boundary is implemented.

## 3. HTTP and polling (G2)

- [ ] 3.1 Implement Spring-managed Netty endpoint/TLS profiles and HTTP codecs; preserve gateway auth, single/batch/import responses and health/control endpoints during cutover.
- [ ] 3.2 Implement bounded Netty HTTP polling clients under existing scheduler/lease ownership; bound response decode/decompression/redirects/egress and isolate slow sources.
- [ ] 3.3 Verify HTTP/import partial results, HTTP overload, polling cancellation, lease loss and route rollback against the frozen contracts.

## 4. MQTT broker client (G3)

- [ ] 4.1 Select and integrate a qualified Netty-backed broker client with protocol/session/auth/QoS support; retain the external broker and registry mapping boundary.
- [ ] 4.2 Implement controlled ACK, bounded in-flight/prefetch, retry/backoff, session/client identity and replica subscription ownership; distinguish broker ACK from application ingestion success.
- [ ] 4.3 Verify QoS0 loss disclosure, QoS1/2 redelivery, persistent-session restart, pending admission failure, invalid payload, reconnect storms and old/new consumer cutover without duplicate effects.

## 5. Industrial clients (G4)

- [ ] 5.1 Integrate qualified Netty-backed Modbus transport or replace the client after G0; retain register/unit/transaction semantics, deadlines and per-source fenced polling.
- [ ] 5.2 Integrate qualified Netty-backed OPC UA transport or replace the client after G0; retain SecureChannel/session/subscription/security, bounded notification queues and gap reporting.
- [ ] 5.3 Verify both protocols with representative peers, config replacement, slow/disconnected sources, stale queued callbacks, lease transfer, cert/auth failures and recovery; mark unqualified modes BLOCKED.

## 6. Lifecycle, qualification and release (G5)

- [ ] 6.1 Implement readiness, bounded drain/shutdown, connection migration, config-generation cutover and opt-in routing/rollback for each ingress mode with a single active path.
- [ ] 6.2 Run ET01–ET10 contract/failure/replica tests, including duplicate pending/late publish races, revoke/config races, source positions, ordering and no unbounded callback or executor queues.
- [ ] 6.3 Run the frozen ET11–ET12 comparative profiles for HTTP, MQTT, polling, Modbus, OPC UA and mixed/burst/degraded conditions; reconcile unique outcomes and report per-claim verdicts and limits.
- [ ] 6.4 Complete focused Maven tests, OpenSpec/structure checks and applicable integration qualification; update current architecture/API/operations docs and evidence links before default enablement or archiving.

All boxes deliberately remain unchecked. Proposal validation does not implement
or qualify these tasks. This request runs no runtime or load qualification.
