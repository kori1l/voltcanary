## ADDED Requirements

### Requirement: Generic capability ownership replaces specialized implementation

The retained transit scenarios SHALL be implemented through Common data/map/rule workbench and dashboard capabilities (G6). This superseded delta SHALL NOT introduce transit-only core tables, an independent engine/ledger or a hardcoded required-stop condition type. Normative VCL/storage/import/adapter contracts apply; old scenario semantics remain acceptance constraints rather than a separate implementation queue.

#### Scenario: Transit scenario is implemented
- **WHEN** a team implements the retained HSL example
- **THEN** it uses the current generic capability owners and actual source evidence, preserving mandatory policy and unknownness without reviving T1–T7 as separate work



### Requirement: Manual monitoring lifecycle

Users SHALL configure and inspect the full required-stop workflow through ordinary authenticated UI/API without AI generation.

#### Scenario: AI disabled
- **WHEN** a user configures an authorized bus, mandatory stop and 30m rule manually
- **THEN** the rule can evaluate and alert while AI generation and the browser are unavailable

#### Scenario: Unknown obligation
- **WHEN** reference data does not establish required service
- **THEN** the UI requests explicit approved stop selection and does not preselect all stops

### Requirement: Shared truthful evidence presentation

Dashboard capabilities SHALL provide authorized pinned stop/zone layers, measured door/speed evidence and server visit/coverage outcomes through the common preview/renderer.

#### Scenario: No history
- **WHEN** a newly connected bus has no telemetry yet
- **THEN** the view shows waiting/unknown rather than synthetic live door or stop values

#### Scenario: Overlapping platforms
- **WHEN** two nearby stop circles overlap
- **THEN** the view shows their distinct identities and evaluator ambiguity rather than merging them

### Requirement: Reviewed and saved configuration

Private dashboard creation/update SHALL preserve the exact reviewed definition and evidence references with ordinary concurrency and receipt semantics.

#### Scenario: Create receipt
- **WHEN** the user confirms a transit dashboard
- **THEN** the initial persisted definition is verified against the review without automatic publication

#### Scenario: Later edit
- **WHEN** the user changes the draft after creation
- **THEN** reconciliation still proves the original creation result and does not overwrite the newer draft


