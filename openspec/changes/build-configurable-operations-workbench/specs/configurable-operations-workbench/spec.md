## ADDED Requirements

### Requirement: Extend the existing Vehicles workflow with AI

Vehicle monitoring SHALL reuse the existing Vehicles console, fleet map, vehicle/source selection and zone editor. Manual and AI changes SHALL use the same authorized telemetry monitoring owner, versioned review and persisted settings. This scenario SHALL NOT create a separate transport dashboard or standalone circle-zone widget.

#### Scenario: AI changes the selected vehicle
- **WHEN** a user asks the assistant to add a zone or change monitoring thresholds for the selected authorized vehicle
- **THEN** the assistant prepares an exact before/after review in the existing Vehicles context, preserves omitted settings and zones, and saves only after confirmation through the shared owner boundary

#### Scenario: Concurrent manual changes
- **WHEN** settings change after an AI or manual review was prepared
- **THEN** confirmation rejects the stale base and requires a new review; replay of an already successful confirmation returns its original receipt without overwriting the newer settings

#### Scenario: Missing GPS and ambiguous selection
- **WHEN** a vehicle has no usable position or the requested vehicle/source/zone is ambiguous
- **THEN** the existing map permits explicit zone-center selection without fabricating a vehicle position, and unresolved identity requires clarification before preparation

#### Scenario: Uncertain monitoring save
- **WHEN** a confirmation response is lost or its review expires during recovery
- **THEN** the UI and assistant inspect the original review and receipt without generating a replacement mutation

#### Scenario: Explicit map centre for AI
- **WHEN** the user chooses a point for AI on the existing Vehicles zone map
- **THEN** the assistant receives that bounded centre as explicit user context without changing active settings or interpreting it as a measured vehicle position

#### Scenario: Honest monitoring outcome
- **WHEN** a zone configuration is saved
- **THEN** the result identifies the selected vehicle and persisted settings in Vehicles and does not claim durable events, activated rules or notifications from client-derived history alone

### Requirement: Equivalent reviewed UI contracts for native and external data

Workbench SHALL expose native/external/projected source modes, strict validation results, versioned diffs, explicit partial/unknown outcomes and current capabilities through the same canonical contracts used by AI.

#### Scenario: External data editing
- **WHEN** a connection supports reading but not verified mutation
- **THEN** the UI exposes reads and explains the unavailable edit capability without issuing best-effort writes

#### Scenario: Partial import
- **WHEN** a chunked job stops after committed chunks
- **THEN** the UI reports exact committed/pending/error counts and does not claim atomic success


### Requirement: Manual generic authoring

Users SHALL configure supported data, geometry, derivations and alert rules through ordinary authenticated UI/API with AI generation disabled.

#### Scenario: Custom workflow
- **WHEN** a company creates a customer-defined place table and duration rule
- **THEN** the editor uses declared fields/operators rather than a predefined transit form

#### Scenario: Schema impact
- **WHEN** a referenced field or zone revision changes
- **THEN** the UI shows affected rules/views and requires renewed review

### Requirement: Shared truthful previews

Workbench and dashboards SHALL use the same canonical definitions, owner capabilities and authorized evidence as AI reviews.

#### Scenario: No data
- **WHEN** a source has not supplied usable observations
- **THEN** the preview shows unknown/waiting instead of generated evidence

#### Scenario: Saved result
- **WHEN** a user confirms configuration created through either UI or AI
- **THEN** receipts prove exact initially persisted definitions and later manual edits are preserved



### Requirement: Truthful review and progress presentation

The workbench SHALL display nullable clear distinctly from unchanged values, incomplete validation counts, owner review digests, partial jobs and unknown receipts using the shared control protocol.

#### Scenario: Revision two implementer boundary
- **WHEN** an import has not reached EOF or a remote operation times out after dispatch
- **THEN** the UI does not claim complete validation or rollback and exposes the existing owner reconciliation outcome
