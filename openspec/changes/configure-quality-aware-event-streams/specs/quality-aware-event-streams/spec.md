## ADDED Requirements

### Requirement: Strict mappings and qualified capture completeness

Source and import mappings SHALL use explicit versioned field/type/unit/null/time conversions and independently declared capture capabilities; sampling, weak polling or missing deletion capture SHALL NOT qualify negative temporal conclusions.

#### Scenario: Polling lacks deletes
- **WHEN** an external adapter supplies updates but cannot detect removals
- **THEN** its projection reports incomplete deletion knowledge and absence-by-row-deletion is unsupported

#### Scenario: Ambiguous local time
- **WHEN** an imported source time falls in a DST fold without offset/policy
- **THEN** validation rejects it rather than using host timezone


### Requirement: Typed observable capabilities

Sources SHALL advertise available field types, identities, units, event kinds and capture policies for generic rules.

#### Scenario: Door or machine state
- **WHEN** two providers expose distinct boolean field names
- **THEN** reviewed mappings make their typed fields usable without adding a scenario-specific evaluator

#### Scenario: Missing work evidence
- **WHEN** a vehicle only supplies motion samples
- **THEN** the platform does not present them as confirmed employee work intervals

### Requirement: Quality-preserving capture

Event capture SHALL preserve required transitions and intervals with durable delivery, event-time provenance and explicit gaps; sampling SHALL NOT imply full coverage.

#### Scenario: Brief transition
- **WHEN** a boolean changes twice between map refreshes
- **THEN** a transition-preserving capture policy retains both observations

#### Scenario: Capture loss
- **WHEN** a spool overflows or the source disconnects
- **THEN** quality becomes degraded and downstream absence rules cannot silently infer a negative fact

### Requirement: Actual input generation and scoped journal readiness

The accepted-input owner SHALL persist a separate input generation with explicit
checkpoint/time cutover atomically with generation-bearing facts. Historical
generation-less facts SHALL retain their original payloads and receipts. Owner
inspection SHALL NOT create generations or infer upstream continuity, qualified
event time, negative-evidence readiness or evaluator readiness from journal data.

#### Scenario: Legacy replay before first new-generation input
- **WHEN** an original accepted-event receipt is replayed after the generation migration
- **THEN** the original receipt is returned unchanged and no generation is minted by replay or inspection

#### Scenario: Fresh signed capture inspection
- **WHEN** the source owner retrieves actual telemetry readiness and source/account authority changes during that read
- **THEN** it rejects the stale description instead of returning usable owner metadata

#### Scenario: Journal progresses under a captured metadata pin
- **WHEN** the same generation and schema remain authorized while the journal head advances
- **THEN** the compiler adapter may retain its original captured head, rejects head regression and grants no sealed row snapshot or temporal execution authority



#### Scenario: Exact bounded accepted-journal prefix
- **WHEN** a signed source owner requests retained facts for an exact generation/schema and checkpoint cut
- **THEN** the telemetry owner returns only a bounded contiguous accepted prefix, preserves same-time facts and exact Boolean/TEXT knowledge, rejects generation/schema drift or missing facts, and grants no upstream completeness, sealed snapshot or execution authority

#### Scenario: Source authority revoked during an observation read
- **WHEN** source/account authority changes while a journal page is fetched
- **THEN** the source owner rejects the page before disclosure and no cached pins or caller-supplied account fields restore authority

#### Scenario: Accepted payload integrity unavailable or changed
- **WHEN** a requested retained fact lacks its original canonical-payload checksum or its stored bytes no longer match
- **THEN** reading fails explicitly without filtering the fact, manufacturing an empty complete page or backfilling historical integrity; original event replay preserves the retained payload and generation

### Requirement: Pinned typed conversion pipeline

Source mappings SHALL use ordered typed conversions with declared source units and pinned registry versions; missing/null/empty and unknown eligibility remain distinct.

#### Scenario: Revision two implementer boundary
- **WHEN** text voltage in V is imported to a kV field
- **THEN** explicit parse and unit conversion yield the correct decimal value; identity unit relabel is rejected

### Requirement: Operational evidence and stable contextual routing

Source bindings SHALL preserve door and other operational transitions independently of latest-position coalescing, expose truthful gaps and route observations to the correct active occurrence contexts. A latest-only map feed SHALL NOT qualify absence of short events.

#### Scenario: Original bus request boundary
- **WHEN** OPEN and CLOSED occur between forwarded GPS samples or next_stop changes before an active visit exits
- **THEN** operational facts are retained or coverage becomes UNKNOWN; the old visit is not silently starved/rebound
