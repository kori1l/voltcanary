## ADDED Requirements

### Requirement: Generic receipt and schema grounded continuation

Continuation SHALL resolve configured datasets, fields, spatial features, source bindings and rule references from scoped metadata and successful receipts, preserving current VCL/import/adapter capability contracts without requiring transit-specific core handlers.

#### Scenario: Held-out schema continuation
- **WHEN** a user refers to a previously configured customer dataset or external source
- **THEN** the assistant binds its authorized actual identity/schema and never substitutes a similarly named resource

#### Scenario: Unknown external mutation
- **WHEN** a dependent external mutation outcome is UNKNOWN
- **THEN** continuation waits for the original owner/adapter receipt rather than creating another resource



### Requirement: Durable created-device conversation referent
The system SHALL resolve device dashboard continuations from authorized confirmed
execution results across reload and SHALL preserve the selected device/source in the
dashboard task. Proposed or failed creation SHALL NOT establish a created-device fact.

#### Scenario: Continue after creating a bus
- **WHEN** a user successfully creates a bus and asks for a dashboard “for this bus” in the same conversation
- **THEN** the server selects that created device, including after conversation recovery
- **AND** reads its actual data capabilities before generating dashboard operations.

#### Scenario: Ambiguous or unavailable referent
- **WHEN** several created vehicles are plausible or the referenced device is unavailable
- **THEN** the assistant asks a concrete selection question or reports unavailable access
- **AND** does not silently choose another device.

### Requirement: Grounded useful device presentation
The system SHALL propose compatible widgets using actual available data and SHALL
distinguish missing/stale data, technical planning faults and real user choices.

#### Scenario: Broad useful-data request
- **WHEN** the user asks to show all useful data about a connected bus
- **THEN** the candidate uses only actual capability references and compatible map/value/history views
- **AND** does not invent fuel, temperature or other unavailable measurements.

#### Scenario: Model contract fault
- **WHEN** generated device parameters violate the internal intent contract
- **THEN** the system records the fault and performs only bounded safe internal repair or returns a truthful technical failure
- **AND** does not instruct the user to edit schemas or unsupported intent fields.

#### Scenario: No accepted earlier dashboard
- **WHEN** dashboard preparation fails before any review has been accepted
- **THEN** the assistant preserves the request and created device without claiming an earlier dashboard exists in history.
