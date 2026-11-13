## ADDED Requirements

### Requirement: Generic capability ownership replaces specialized implementation

The retained transit scenarios SHALL be implemented through External source adapter + approved generic dataset mappings/policy records (GX/G1/G5). This superseded delta SHALL NOT introduce transit-only core tables, an independent engine/ledger or a hardcoded required-stop condition type. Normative VCL/storage/import/adapter contracts apply; old scenario semantics remain acceptance constraints rather than a separate implementation queue.

#### Scenario: Transit scenario is implemented
- **WHEN** a team implements the retained HSL example
- **THEN** it uses the current generic capability owners and actual source evidence, preserving mandatory policy and unknownness without reviving T1–T7 as separate work



### Requirement: Versioned authoritative reference data

The platform SHALL expose bounded, authorized route/trip/platform-stop snapshots with provider namespace, effective service dates, ordered stop occurrences, source URI, retrieval time and immutable content identity.

#### Scenario: Snapshot refresh
- **WHEN** a newer feed is imported during an active trip
- **THEN** the running visit retains its pinned snapshot and a changed import does not silently mutate accepted configuration

#### Scenario: Untrusted package
- **WHEN** an archive contains traversal paths, excessive expansion or unresolved references
- **THEN** the import fails without installing partial reference data

### Requirement: Unambiguous trip and occurrence resolution

Trip matching SHALL distinguish physical vehicle, passenger route label, service date, direction, trip instance and repeated stop sequence, returning unresolved status instead of guessed identities.

#### Scenario: Direction and midnight
- **WHEN** HFP topic direction 2 and a post-midnight service observation are resolved
- **THEN** the tested provider mapping selects GTFS direction 1 and the correct operating-day trip, or returns ambiguity

#### Scenario: Repeated stop
- **WHEN** a loop visits the same stopId twice
- **THEN** the occurrences retain different stopSequence identities

### Requirement: Explicit required-service policy

Only confirmed user selections or an authoritative imported operational policy SHALL establish required door service; schedule inclusion and regular pickup permissions alone SHALL NOT establish it.

#### Scenario: No operational policy
- **WHEN** a trip has regular scheduled stops but no approved mandatory subset
- **THEN** activation remains blocked and the user is asked to select or import a required-stop policy

#### Scenario: Confirmed subset
- **WHEN** the user approves two exact stop occurrences
- **THEN** the immutable policy records those occurrences and its provenance without expanding to all stops


