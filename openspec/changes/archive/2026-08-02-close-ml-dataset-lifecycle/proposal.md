## Why

The current ML data workflow is not closed. Internal telemetry dataset versions
store validation metadata and query lineage, but they do not freeze the selected
rows into an immutable training artifact. External CSV/JSONL imports store
imported rows, but do not create `READY` dataset versions that the feature and
training workflow can consume.

Before model work can become production-grade, VoltCanary needs dataset
versions that are real data products: reproducible, immutable, quality-checked,
and usable by downstream feature generation without database scraping or manual
repair.

## What Changes

- Make external imports create dataset versions.
- Materialize internal telemetry selections into immutable canonical ML rows.
- Add dataset artifact URI/hash metadata and data quality profile fields.
- Strengthen import validation for metric/unit semantics and device ownership.
- Add a frontend/data-engineer workflow for creating internal telemetry
  datasets.
- Update API and operations docs so `READY` has a concrete meaning: the dataset
  can be used for feature generation and training.

## Capabilities

### New Capabilities

- `ml-dataset-lifecycle`: immutable internal/external dataset versions,
  canonical ML rows, lineage, validation, and operator workflows.

## Impact

- `backend/services/analytics-service/**`: dataset version creation, import
  flow, validation reports, lineage, artifact metadata, tests.
- `backend/services/telemetry-service/**`: historical export API or stable
  extraction support for dataset materialization.
- `backend/services/device-service/**`: device ownership/type validation for
  imported device mappings.
- `frontend/src/**`: internal telemetry dataset creation workflow and dataset
  readiness display.
- `docs/api/**` and `docs/operations/**`: dataset lifecycle contracts and
  operator procedures.
- `deploy/**`: object storage configuration if dataset artifacts are stored in
  MinIO/S3.

## Dependencies

This is the first phase. Later feature, training, failure-label, and deployment
changes depend on it.
