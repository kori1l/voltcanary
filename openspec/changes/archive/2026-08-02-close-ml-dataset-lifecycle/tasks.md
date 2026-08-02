## 1. Dataset Version Contract

- [x] 1.1 Define canonical ML row fields for internal telemetry and external imports.
- [x] 1.2 Add dataset artifact URI/hash metadata or immutable row-reference metadata to dataset versions.
- [x] 1.3 Extend validation reports with data quality profile fields.
- [x] 1.4 Update API documentation for dataset version readiness semantics.

## 2. External Import Lifecycle

- [x] 2.1 Make CSV import create a dataset version.
- [x] 2.2 Make JSONL import create a dataset version.
- [x] 2.3 Attach import validation reports to the dataset version, not only the imported file.
- [x] 2.4 Mark external dataset versions `READY` only when accepted canonical rows exist.
- [x] 2.5 Add tests for import-to-ready-dataset-version workflow.

## 3. Internal Telemetry Materialization

- [x] 3.1 Add stable extraction/materialization for selected telemetry rows.
- [x] 3.2 Normalize metric names and units during materialization.
- [x] 3.3 Persist immutable canonical rows or artifact references for each dataset version.
- [x] 3.4 Compute deterministic data hash for materialized internal telemetry datasets.
- [x] 3.5 Add tests proving repeated materialization produces the same data hash for unchanged input.

## 4. Ownership And Quality Gates

- [x] 4.1 Verify imported `existingDeviceId` values belong to the importing account.
- [x] 4.2 Reject or quarantine imported rows with invalid device ownership.
- [x] 4.3 Add metric/unit compatibility validation.
- [x] 4.4 Add coverage, duplicate, sampling-gap, and impossible-value quality metrics.
- [x] 4.5 Add tests for ownership and quality-gate failures.

## 5. Frontend Workflow

- [x] 5.1 Add internal telemetry dataset creation UI.
- [x] 5.2 Show dataset artifact/hash, quality profile, and lineage in dataset detail.
- [x] 5.3 Ensure imported datasets appear as dataset versions after import.
- [x] 5.4 Add frontend tests for import and internal dataset creation readiness states.

## 6. Verification

- [x] 6.1 Run backend analytics-service tests covering dataset/import workflows.
- [x] 6.2 Run frontend tests for ML dataset workflows.
- [x] 6.3 Run `npm run openspec:validate`.
