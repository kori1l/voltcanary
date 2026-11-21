## ADDED Requirements

### Requirement: Transport Execution Is Bounded And Fair

Ingestion SHALL bound connections, queued items/bytes, in-flight work, keyed lanes,
outbound bytes and CPU/blocking executors. It SHALL enforce pre-authentication
limits and authenticated source/account/protocol fairness without replacing
authoritative shared rate limits.

#### Scenario: One protocol floods ingestion

- **WHEN** one source, account, protocol or import lane exhausts its allocation
- **THEN** ingestion SHALL reject, defer or pause according to the protocol contract
- **AND** other reserved shares SHALL continue within pre-frozen progress budgets
- **AND** no queue, callback buffer or caller-runs fallback SHALL evade the limits.

### Requirement: Event Loops Perform Only Bounded Nonblocking Work

Netty and SDK I/O callbacks SHALL NOT run blocking core/external I/O or unbounded
CPU parsing. Offload SHALL have finite concurrency, byte/item budgets and deadlines.

#### Scenario: Kafka or source recovery stalls

- **WHEN** the current blocking publisher or source lookup reaches its timeout
- **THEN** it SHALL run outside I/O loops in a bounded isolated execution boundary
- **AND** transport and unrelated allocated sources SHALL remain responsive.

### Requirement: Endpoint And Decode Policies Are Explicit

Every listener/client SHALL declare protocol, endpoint, security and size/deadline
limits. TCP framing SHALL handle arbitrary fragmentation/coalescing. Shared ports
SHALL require a tested negotiation contract rather than packet-boundary inference.

#### Scenario: A peer slowly sends an oversized fragmented frame

- **WHEN** declared length, expanded size or absolute partial-frame deadline exceeds policy
- **THEN** the protocol handler SHALL terminate/reject within configured bounds
- **AND** SHALL NOT allocate the declared oversized payload or bypass TLS/auth policy.

### Requirement: Resource Ownership Survives Asynchronous Completion

Channel/session state SHALL remain single-owner and channel-affine. Shared
adapters SHALL be safe for concurrent calls. Cross-thread buffers/context and
admission permits SHALL have explicit bounded ownership and exactly-once cleanup.

#### Scenario: A retained buffer outlives a client request

- **WHEN** cancellation, timeout, exception or shutdown races with worker completion
- **THEN** buffer references and permits SHALL be released exactly once after their work terminates
- **AND** late completion SHALL NOT write a second response or leak trace/credential context.

### Requirement: Admission Is Distinct From Durable Completion

In publishing deployments, successful publication SHALL require the configured
Kafka acknowledgement. Memory admission or an idempotency reservation SHALL NOT
be represented as durable success. Filtered and duplicate outcomes SHALL remain distinct.

#### Scenario: A retry finds a pending reservation

- **WHEN** the original attempt has not established publication completion
- **THEN** the retry SHALL await the same bounded completion or receive retryable/unknown status
- **AND** SHALL NOT receive published success solely because the reservation exists.

#### Scenario: Kafka completes after a transport timeout

- **WHEN** publication may have occurred but the caller has disconnected or timed out
- **THEN** ingestion SHALL preserve bounded ownership of late completion and logical identity
- **AND** SHALL NOT equate timeout with proven non-publication or blindly permit conflicting concurrent retry.

### Requirement: Ordered Offload Preserves Canonical Semantics

Concurrent execution SHALL preserve declared authenticated source/device admission
order, sourcePosition/gap policy and device publication lanes with bounded state.
Local lanes SHALL NOT be advertised as cross-replica total ordering.

#### Scenario: A later input decodes faster

- **WHEN** two ordered source inputs complete CPU work in reverse order
- **THEN** publication SHALL respect their declared lane order
- **AND** schema/processing generation, authorization, sourcePosition and import distinctions SHALL remain intact.

### Requirement: Qualification Uses Frozen Comparable Workloads

Full transport enablement SHALL require ET01–ET12 from the target transport
contract, a pre-frozen numerical workload/SLO/resource manifest, all protocol modes
and reconciled unique outcomes. Smoke or historical failed runs SHALL NOT prove capacity.

#### Scenario: No numerical target has been agreed

- **WHEN** implementation qualification has no frozen workload and numerical budgets
- **THEN** the performance gate SHALL remain BLOCKED
- **AND** SHALL NOT claim a measured target throughput, connection capacity or scaling gain.

#### Scenario: A mixed protocol comparison is measured

- **WHEN** baseline and target run under the frozen comparable topology
- **THEN** evidence SHALL cover per-mode and mixed/burst overload, slow sources, dependency degradation and recovery
- **AND** SHALL report latency sample counts, offered/unique published/persisted/unknown outcomes, resource/lag bounds and per-claim verdicts.
