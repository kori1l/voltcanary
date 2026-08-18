# Design

## Backend

Analytics-service exposes `GET /analytics/ml/datasets/feature-snapshots` on the
existing ML dataset controller. The endpoint resolves the caller account the
same way as dataset listing and delegates to `MlFeaturePipelineService`.

The service returns only `ACTIVE` feature snapshots by default, ordered newest
first by repository methods. An optional `datasetVersionId` query parameter
switches to the dataset-scoped repository method while preserving account and
active-status filtering.

`MlFeatureSnapshotResponse` includes the metadata needed by frontend training
compatibility checks: dataset version id, model family, horizon days, template
id/version, hashes, row counts, lifecycle status, and created timestamp.

## Frontend

ML Settings loads the snapshot inventory with the other ML workspace resources
and stores it as an array. Generated snapshots are upserted immediately and the
workspace refresh is still used as the server-authoritative source. Archive and
delete actions remove the snapshot from local state before refresh so the
default active selector does not keep a stale lifecycle target visible.

The Dataset tab shows all snapshots for the selected dataset version. The
Training tab filters snapshots by selected dataset version, explains compatible
snapshots for the selected model family, and blocks queueing when no snapshot is
selected, the snapshot is unavailable, the model family differs, or a
failure-prediction horizon differs.

## Validation

Backend validation covers account-wide and dataset-scoped active snapshot
listing. Frontend regression coverage verifies that the training selector can
choose an older compatible snapshot from the inventory and still submits that
snapshot id.
