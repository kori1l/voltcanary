## Why

The current feature snapshot implementation stores metadata and small samples,
not a loadable feature matrix. Internal telemetry snapshots can report no usable
feature rows, and live inference sends aggregate heuristic fields that do not
match offline training features.

Production ML requires a feature platform where offline training and online
inference use the same feature contract, feature snapshots are materialized, and
feature schema/data hashes are persisted for traceability.

## What Changes

- Materialize full feature matrices for dataset versions.
- Add canonical feature vector contracts by model family and horizon.
- Add online feature generation that matches offline feature generation.
- Store feature schema hash, feature data hash, row count, rejected row count,
  and artifact URI.
- Add device-type feature templates for the first supported device categories.
- Add feature parity tests and feature incompatibility behavior.

## Capabilities

### New Capabilities

- `ml-feature-platform`: materialized feature snapshots, online/offline feature
  parity, feature hashes, and device-type feature templates.

## Impact

- `backend/services/analytics-service/**`: feature snapshot orchestration and
  online feature generation boundary.
- `ml-service/**`: feature transformation code if feature generation is moved
  to or shared with Python.
- `backend/services/telemetry-service/**`: historical extraction support for
  feature windows.
- `frontend/src/**`: feature snapshot status, hashes, templates, and errors.
- `docs/api/**` and `docs/operations/**`: feature contracts and verification.

## Dependencies

Depends on `close-ml-dataset-lifecycle` for immutable dataset rows/artifacts.
