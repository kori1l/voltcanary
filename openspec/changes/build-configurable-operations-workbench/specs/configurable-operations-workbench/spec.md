## ADDED Requirements

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
