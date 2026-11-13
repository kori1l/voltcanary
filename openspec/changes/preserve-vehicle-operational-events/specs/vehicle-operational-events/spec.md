## ADDED Requirements

### Requirement: Generic capability ownership replaces specialized implementation

The retained transit scenarios SHALL be implemented through Generic observation mapping/capture quality (G3) and adapter capabilities (GX). This superseded delta SHALL NOT introduce transit-only core tables, an independent engine/ledger or a hardcoded required-stop condition type. Normative VCL/storage/import/adapter contracts apply; old scenario semantics remain acceptance constraints rather than a separate implementation queue.

#### Scenario: Transit scenario is implemented
- **WHEN** a team implements the retained HSL example
- **THEN** it uses the current generic capability owners and actual source evidence, preserving mandatory policy and unknownness without reviving T1–T7 as separate work



### Requirement: Durable operational observation path

The system SHALL preserve accepted allowlisted door, stop, journey and position observations independently of latest-position sampling, retaining source time, optional GPS, typed state and source identity.

#### Scenario: Short opening
- **WHEN** DOO and DOC occur between two five-second map updates
- **THEN** both operational observations remain queryable and the map may still coalesce positions

#### Scenario: Door without GPS
- **WHEN** a valid bound door event lacks coordinates
- **THEN** the event is stored with missing-location quality instead of being dropped or fabricating coordinates

### Requirement: Idempotent delivery and identity

Capture, ingress and storage SHALL recover with stable source keys and durable acknowledgements, without claiming distributed exactly-once upstream delivery.

#### Scenario: Crash after acceptance
- **WHEN** ingress commits but the connector misses the ACK and reconnects
- **THEN** retry uses the same source key and creates no duplicate accepted observation

#### Scenario: Same timestamp
- **WHEN** VP, DOO and DOC have the same source timestamp
- **THEN** different event types are retained and contradictory ordering is exposed as uncertain

### Requirement: Explicit coverage and bounded overload

The platform SHALL record capture interruptions and bounded-buffer losses, preserve UNKNOWN door state, and distinguish observed coverage from guaranteed physical completeness.

#### Scenario: Overflow
- **WHEN** the bounded spool cannot retain another event
- **THEN** capture marks a degraded interval, exposes a metric and prevents a silent complete-coverage claim

#### Scenario: Missing door field
- **WHEN** a position has no door measurement
- **THEN** the observation retains UNKNOWN rather than CLOSED


