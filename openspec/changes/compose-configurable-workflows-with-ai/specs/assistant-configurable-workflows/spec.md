## ADDED Requirements

### Requirement: Capability gated native table authoring

The first native table skill SHALL remain disabled by default and SHALL use the
ordinary signed owner reservation, preparation, review and apply contracts. The
server SHALL bind actual reserved identities, limit AI review expiry to the owner
lease and require explicit confirmation. Success SHALL require the original
successful receipt and exact schema readback. The UI SHALL share the manual
schema preview and preserve the reviewed document semantics.

#### Scenario: Lost creation acknowledgement
- **WHEN** native apply may have committed but its response is unavailable
- **THEN** the saved action remains UNKNOWN and recovery uses the original review and key without another reservation

#### Scenario: Expired unknown review
- **WHEN** the saved native AI review has expired with an UNKNOWN result
- **THEN** recovery may only inspect the original owner review/resource with GET and cannot renew apply authority or POST reconciliation

### Requirement: AI adherence to language adapter and import contracts

AI SHALL manipulate only declared VCL/owner methods, typed accessible schemas and verified adapter capabilities, using the same sealed imports, review/CAS/receipts and revocable execution grants as ordinary users.

#### Scenario: Plausible SQL proposal
- **WHEN** a model returns a SQL fragment or unregistered conversion
- **THEN** the proposal is rejected before external database execution

#### Scenario: Unknown remote result
- **WHEN** a mutation committed remotely but the model sees timeout
- **THEN** the plan reconciles the original receipt/key and cannot retry with a new identity

#### Scenario: Generic source task
- **WHEN** a customer describes a held-out schema and ambiguous work metric
- **THEN** the assistant discovers authorized fields/capabilities and asks a grounded clarification without inventing source semantics


### Requirement: Cross-domain capability composition

The assistant SHALL discover authorized schemas/relations/operators and compose supported workflows through generic registered operations rather than one hardcoded intent per business scenario.

#### Scenario: Novel customer vocabulary
- **WHEN** a held-out company uses different table and field labels for the same logical relationships
- **THEN** the assistant resolves schema identities and produces an equivalent typed plan

#### Scenario: Work definition ambiguity
- **WHEN** the user asks to monitor overtime but only vehicle motion is available
- **THEN** the assistant clarifies the subject/source and cannot label motion as confirmed employee work

### Requirement: Reviewed grounded mutation

Every data/schema/map/rule mutation SHALL use the ordinary owner validation, reviewed diff, explicit consent and receipt-backed dependency identities.

#### Scenario: Compound setup
- **WHEN** a workflow creates a collection, places, rule and dashboard
- **THEN** review enumerates the mutations and dependent IDs resolve from successful receipts

#### Scenario: Partial failure
- **WHEN** a later owner action fails after earlier resources were created
- **THEN** resume preserves successful receipts and pending goals without duplicate creation

### Requirement: Bounded model assistance

Specialists and model routing SHALL remain coordinator-owned, scoped, qualified and charged to one durable parent budget, with external content treated only as untrusted evidence.

#### Scenario: Complex research
- **WHEN** AUTO uses different authorized qualified models for research and schema filling
- **THEN** all assignments/attempts/prices are recorded without changing tool permissions

#### Scenario: Late or exhausted worker
- **WHEN** a cancelled or over-budget worker attempts completion
- **THEN** no unreserved call or stale overwrite occurs



### Requirement: Reserved identities and dependency resolution

The assistant SHALL keep symbolic local references outside executable VCL, reserve IDs through owners and block dependent applies until exact creation receipts succeed and production metadata is recompiled.

#### Scenario: Revision two implementer boundary
- **WHEN** an earlier creation receipt is UNKNOWN or resolves a different reserved ID
- **THEN** the dependent rule cannot activate or substitute an object with a matching label

### Requirement: Typed requirements and bounded specialist consultation

The assistant SHALL use revisioned requirement slots, scoped typed consultation requests/results and a durable bounded task tree with shared budgets, cancellation fences and a root question arbiter. Consultation traversal SHALL use the single qualified library graph runtime from adopt-langgraph4j-agent-orchestration rather than a second custom scheduler. Optional RAG, when enabled, SHALL return authorized versioned evidence; metrics SHALL resolve through exact owner APIs. RAG and full configurable-owner completion SHALL NOT be prerequisites for a supported existing-capability consultation slice.

#### Scenario: Consultation and retrieval boundary
- **WHEN** a schema specialist asks a metrics specialist which needs another specialist or user policy
- **THEN** the scheduler enforces depth/access/budgets, coalesces user questions at root, preserves provenance and blocks activation with unresolved required slots

#### Scenario: Library graph and worker infrastructure
- **WHEN** consultation work resumes through a Spring executor or due-work scanner
- **THEN** the one library graph owns traversal while the adapter enforces durable leases and current access without an independent graph scheduler

### Requirement: Dependency-specific consultation freshness and exact goal coverage

The assistant SHALL validate assignments and dependency versions rather than reject every child on an unrelated global revision change; accepted rebase SHALL preserve original provenance without a new model call. Requirement coverage SHALL bind every required goal to exact owner effects and postconditions.

#### Scenario: Revision two dependency and quality boundary
- **WHEN** an unrelated slot changes while a child works, or the candidate uses a same-unit wrong metric
- **THEN** the unchanged dependency result may be CAS-rebased, but the wrong metric or changed dependency blocks activation

### Requirement: Managed consultation and retrieval application boundaries

Configurable AI skills SHALL share the Spring implementation pipeline while retaining their own registry contracts and exact postconditions. Consultation work SHALL use durable claims and current authorization, not recursive ToolCallbacks, transient async delivery or a second memory/budget store.

#### Scenario: Spring extension boundary
- **WHEN** a worker restarts after its authenticated session expires or after an after-commit wakeup was lost
- **THEN** durable state is recoverable but external dispatch awaits current authorization; the scanner reconciles work without an unreserved inference or privileged service fallback

### Requirement: Bus request semantic fidelity

The assistant SHALL retain the confirmed required-stops-only scope, distinguish observing door opening from a stronger dwell/speed policy, and report missing capture/occurrence/non-arrival capabilities explicitly.

#### Scenario: Original bus request boundary
- **WHEN** the user asks to alert on no door opening after observed zone departure
- **THEN** the proposal does not silently add a2s minimum dwell or claim detection of never-entered stops, and activation awaits qualified owner capabilities
