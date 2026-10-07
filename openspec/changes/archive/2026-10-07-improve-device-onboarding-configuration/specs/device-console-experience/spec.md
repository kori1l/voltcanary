## ADDED Requirements

### Requirement: Scannable fleet inventory

Devices SHALL provide a compact fleet table alongside location/card views, with name, location, registry state, connection freshness and risk; search, filters and first-use states SHALL remain readable across widths.

#### Scenario: An operator scans a large fleet

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the table view or filters are selected
- **THEN** devices can be compared without opening each card and inactive registration is not confused with missing telemetry.

### Requirement: Recoverable four-step onboarding

The Basics, Location, Telemetry and Review wizard SHALL distinguish current/completed steps, retain draft state when going back, keep actions reachable and summarize source mode and simulator start behavior before creation.

#### Scenario: Setup fails after device creation

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator retries the unfinished source or simulator step
- **THEN** created resources and the failure are shown and setup resumes using their recorded IDs rather than creating a duplicate device.

### Requirement: Clear location and source choices

Onboarding SHALL separate existing/new location selection, group optional building/floor/room/zone/tags fields, and explain source-specific required and advanced parameters.

#### Scenario: An operator changes location or source mode

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator moves backward and forward
- **THEN** compatible values are retained, incompatible required fields are explained and review reflects the current choices.

### Requirement: Previewable CSV imports

Device and source imports SHALL accept files with a downloadable template, supported-column and row preview, row-numbered validation and separate accepted/rejected results. Textarea entry SHALL remain an advanced alternative.

#### Scenario: A CSV contains invalid rows

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator previews and imports the file
- **THEN** preview identifies errors before mutation and the result preserves server-reported partial acceptance without calling the whole import a success.

### Requirement: Readable source sensor and analytics configuration

Configuration SHALL group source and sensor inventories with focused editors, named metric mapping and units, typed known analytics parameters and advanced JSON preserving unknown keys. Credential rotation/revocation SHALL explain the effect on ingestion.

#### Scenario: A configured device is edited

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** a sensor mapping, analytics setting or source credential operation is reviewed
- **THEN** supported values and units are visible and unrelated configuration keys remain intact; consequential token actions require a clear impact review.

### Requirement: Preset-first simulator workspace

Demo Tools SHALL expose presets before advanced parameters, show units, disclose anomaly settings when enabled and distinguish draft, saved configuration and running state. Validate, Save, Start/Stop and run history SHALL remain accessible.

#### Scenario: A simulator draft differs from its saved config

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** an operator reviews or saves it while a run exists
- **THEN** the UI identifies which configuration the run uses, shows validation results and does not represent unsaved changes as active.

