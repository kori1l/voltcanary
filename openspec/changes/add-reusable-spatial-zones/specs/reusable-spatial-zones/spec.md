## ADDED Requirements

### Requirement: Generic capability ownership replaces specialized implementation

The retained transit scenarios SHALL be implemented through Shared spatial resources/map selection (G2), linked through G1 dataset refs. This superseded delta SHALL NOT introduce transit-only core tables, an independent engine/ledger or a hardcoded required-stop condition type. Normative VCL/storage/import/adapter contracts apply; old scenario semantics remain acceptance constraints rather than a separate implementation queue.

#### Scenario: Transit scenario is implemented
- **WHEN** a team implements the retained HSL example
- **THEN** it uses the current generic capability owners and actual source evidence, preserving mandatory policy and unknownness without reviving T1–T7 as separate work



### Requirement: Canonical reusable circle zones

The platform SHALL own authorized immutable zone and zone-set revisions with metre-based geometry, source provenance, concurrency checks and explicit bounded membership.

#### Scenario: Reuse
- **WHEN** two authorized devices use one approved zone-set revision
- **THEN** both reference the same immutable geometry without copied divergent circles

#### Scenario: Too many stops
- **WHEN** a selected route exceeds the configured set/import limit
- **THEN** the user sees a limit and can select a bounded subset; no stop list is silently truncated

### Requirement: Compatible legacy migration

Canonical zone introduction SHALL preserve existing owner/device monitoring settings and require an explicit authorized import before legacy circles become reusable rule resources.

#### Scenario: Existing map
- **WHEN** an existing device still uses its old monitoring endpoint
- **THEN** its circles and first-point/history behavior remain compatible

#### Scenario: Import
- **WHEN** the owner reviews a legacy-circle import
- **THEN** new canonical identities and provenance are created without silently enabling rules

### Requirement: Revision and access safety

Referenced zone revisions SHALL remain immutable and authorized; conflicting edits SHALL fail and invalidate pending reviews instead of rebinding rules.

#### Scenario: Concurrent edit
- **WHEN** a stale If-Match write changes a reviewed radius
- **THEN** the write conflicts and requires renewed review

#### Scenario: Foreign zone
- **WHEN** another account submits a zone ID in a rule
- **THEN** the server rejects it before lookup evidence or activation leaks


