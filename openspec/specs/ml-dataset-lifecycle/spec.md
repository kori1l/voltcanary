# ml-dataset-lifecycle Specification

## Purpose
Defines the lifecycle for immutable ML dataset versions, including external
imports, internal telemetry materialization, dataset readiness, validation
quality profiles, lineage, and operator-facing dataset workflows.
## Requirements
### Requirement: External imports create dataset versions

CSV and JSONL imports SHALL create account-scoped ML dataset versions that can
be validated, inspected, and later materialized into canonical training rows.

#### Scenario: CSV import creates a validating dataset version

- **GIVEN** an authorized operator uploads a CSV file with a valid schema mapping
- **AND** at least one row is accepted
- **WHEN** the import completes
- **THEN** the system SHALL create a dataset version linked to the imported file
- **AND** the dataset version SHALL be marked `VALIDATING`
- **AND** the dataset version SHALL not be marked `READY` until accepted canonical rows exist
- **AND** the validation report SHALL be linked to the dataset version
- **AND** the dataset version SHALL be returned by dataset listing APIs.

#### Scenario: Import rejects every row

- **GIVEN** an import contains no accepted canonical rows
- **WHEN** the import completes
- **THEN** the system SHALL create no `READY` dataset version
- **AND** the operator SHALL receive validation reasons explaining why the file cannot be used for training.

### Requirement: Internal telemetry dataset versions are immutable

Internal telemetry dataset creation SHALL freeze the selected telemetry rows or
store an equivalent immutable artifact reference before marking the dataset
version `READY`.

#### Scenario: Internal telemetry selection is materialized

- **GIVEN** selected devices, metrics, and time range pass validation
- **WHEN** the dataset version is created
- **THEN** the system SHALL materialize canonical ML rows for that selection
- **AND** store an artifact URI or immutable row reference
- **AND** store a deterministic data hash
- **AND** mark the dataset version `READY`.

#### Scenario: Materialization fails

- **GIVEN** telemetry validation succeeds
- **BUT** row materialization or artifact persistence fails
- **WHEN** dataset creation completes
- **THEN** the dataset version SHALL not be marked `READY`
- **AND** a sanitized failure reason SHALL be stored.

### Requirement: Dataset versions expose data quality profiles

Dataset versions SHALL expose enough quality metadata for an operator to decide
whether the data is suitable for model training.

#### Scenario: Operator views dataset detail

- **GIVEN** a dataset version exists
- **WHEN** the operator opens dataset detail
- **THEN** the response SHALL include row count, device count, metric count, time range, coverage, sampling gaps, rejected rows, warnings, and lineage
- **AND** the response SHALL include artifact/hash metadata when available.

### Requirement: Imported device mappings are account-safe

Imported rows that claim an existing device id SHALL be verified against account
ownership before they are accepted as matched-device training data.

#### Scenario: Imported device id belongs to another account

- **GIVEN** a CSV or JSONL row maps to an existing device id
- **AND** the device is not owned by the importing account
- **WHEN** the row is validated
- **THEN** the row SHALL be rejected or quarantined
- **AND** the validation report SHALL include an ownership failure reason.

### Requirement: Internal dataset creation is available to operators

The frontend SHALL provide an operator workflow for creating internal telemetry
dataset versions.

#### Scenario: Operator creates internal telemetry dataset from UI

- **GIVEN** the operator can access selected devices
- **WHEN** the operator selects metrics, time range, and quality gates
- **THEN** the frontend SHALL call the internal telemetry dataset API
- **AND** show resulting status, validation summary, lineage, and artifact/hash information.

### Requirement: Dataset versions support safe cleanup

Dataset versions SHALL be archivable or deletable only when doing so preserves
ML lineage.

#### Scenario: Operator archives a ready dataset

- **GIVEN** a `READY` dataset version exists
- **WHEN** an authorized operator archives it with a reason
- **THEN** the dataset SHALL be hidden from default dataset selectors
- **AND** immutable row references, hashes, validation reports, and lineage SHALL
  remain readable.

#### Scenario: Operator deletes a failed unreferenced dataset

- **GIVEN** a dataset version is `FAILED`
- **AND** no labels, feature snapshots, training runs, or model versions
  reference it
- **WHEN** an authorized operator deletes it with a reason
- **THEN** it SHALL be removed from default dataset lists
- **AND** the action SHALL be audit logged.

#### Scenario: Operator attempts to delete a referenced dataset

- **GIVEN** a dataset version is referenced by labels, feature snapshots,
  training runs, or model versions
- **WHEN** an operator requests hard deletion
- **THEN** the system SHALL block deletion
- **AND** return a dependency summary.

### Requirement: Internal telemetry dataset preview is read-only and authoritative

Analytics-service SHALL provide a read-only preview for internal telemetry
dataset creation that uses backend-owned validation logic and does not mutate ML
lifecycle state.

#### Scenario: Operator previews internal telemetry dataset selection

- **GIVEN** the operator has selected accessible devices, metrics, model family,
  quality gates, and a time range
- **WHEN** the operator requests preview
- **THEN** analytics-service SHALL return available metrics, metric coverage,
  candidate windows, selected window status, quality gates, blocking reasons,
  warning reasons, create defaults, preview id or hash, and expiry
- **AND** no dataset version, canonical row, label, feature snapshot, training
  run, model version, or deployment policy SHALL be created.

#### Scenario: Preview discovers metrics from selected devices

- **GIVEN** selected devices expose different telemetry metrics
- **WHEN** preview runs in common-metric mode
- **THEN** the response SHALL return only metrics present on every selected
  device
- **AND** include coverage counts and ratios for each returned metric.

#### Scenario: Preview allows partial coverage explicitly

- **GIVEN** selected devices expose different telemetry metrics
- **WHEN** preview runs in all-available mode
- **THEN** the response SHALL return the metric union with coverage counts,
  ratios, warnings, and blocking reasons where applicable
- **AND** metrics present on zero selected devices SHALL not be selectable.

#### Scenario: Preview recommends candidate windows

- **GIVEN** selected devices and metrics have telemetry over the searched range
- **WHEN** preview evaluates the selection
- **THEN** the response SHALL include ranked candidate windows for best
  coverage, recent stable data, failure-rich data when applicable, and the user
  selected range
- **AND** each candidate SHALL include status, score, quality summary, and
  explanation.

### Requirement: Dataset creation can be bound to a preview contract

Internal telemetry dataset creation SHALL optionally accept preview identity so
the frontend can detect drift between preview and immutable dataset creation.

#### Scenario: Workbench creates dataset from fresh preview

- **GIVEN** preview returned a ready or warning status with a preview hash
- **WHEN** the operator submits dataset creation with the same selection and
  preview hash
- **THEN** analytics-service SHALL revalidate the request
- **AND** create the immutable dataset version only if the selection still
  satisfies dataset creation rules
- **AND** store selected preview summary in the validation report quality
  profile.

#### Scenario: Workbench submits stale preview

- **GIVEN** telemetry, permissions, maintenance exclusions, labels, selected
  metrics, range, or quality gates changed after preview
- **WHEN** the operator submits dataset creation with stale preview identity
- **THEN** analytics-service SHALL reject or conflict the request with a
  structured stale-preview reason
- **AND** the frontend SHALL be able to request a fresh preview.

#### Scenario: Legacy caller creates dataset without preview

- **GIVEN** a non-workbench caller submits the existing internal telemetry
  dataset request without preview fields
- **WHEN** the request is valid
- **THEN** analytics-service SHALL preserve existing create behavior
- **AND** still run authoritative validation and materialization before marking
  the dataset version `READY`.

### Requirement: Preview timeline carries operator-visible event markers

Analytics-service SHALL include maintenance and failure outcome evidence in
internal telemetry dataset preview timeline buckets.

#### Scenario: Preview range overlaps maintenance or failure outcomes

- **GIVEN** selected devices have maintenance windows or maintenance outcomes
  inside the preview range
- **WHEN** analytics-service returns a dataset workbench preview
- **THEN** matching timeline buckets SHALL include event labels for maintenance
  overlaps, ML exclusions, confirmed failures, corrective maintenance,
  unresolved anomalies, sensor faults, or unknown outcomes
- **AND** the response schema SHALL remain backward-compatible for clients that
  already treat `timeline[].events` as a string array.

#### Scenario: Preview range has no events

- **GIVEN** selected devices have telemetry but no maintenance or failure
  evidence inside the preview range
- **WHEN** analytics-service returns a dataset workbench preview
- **THEN** timeline buckets SHALL still be returned with an empty `events`
  array.

