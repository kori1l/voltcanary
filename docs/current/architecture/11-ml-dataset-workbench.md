# ML Dataset Workbench

This document describes the ML Dataset Workbench behavior that is present in
the current codebase.

## Implemented Surfaces

The frontend workbench is implemented under
`frontend/src/features/ml-datasets/` and mounted from
`frontend/src/pages/MlSettingsPage.tsx`. It is part of the ML Settings
workflow and keeps dataset list/detail, external import, labels, feature
snapshots, training, registry, policy, and monitoring flows available in the
same page.

The browser does not call `ml-service` directly. Browser-facing ML lifecycle
endpoints are owned by `analytics-service` under `/analytics/ml/**` and
`/analytics/ml-settings/**`.

## Current Dataset Endpoints

`analytics-service` exposes the current dataset workbench and lifecycle
surface:

```http
GET  /analytics/ml/datasets
GET  /analytics/ml/datasets/versions/{datasetVersionId}
POST /analytics/ml/datasets/versions/{datasetVersionId}/archive
GET  /analytics/ml/datasets/versions/{datasetVersionId}/lifecycle-impact
POST /analytics/ml/datasets/versions/{datasetVersionId}/delete
POST /analytics/ml/datasets/internal-telemetry
POST /analytics/ml/datasets/internal-telemetry/preview
POST /analytics/ml/datasets/external-csv
POST /analytics/ml/datasets/external-jsonl
POST /analytics/ml/datasets/feature-snapshots
GET  /analytics/ml/datasets/feature-snapshots
POST /analytics/ml/datasets/feature-snapshots/{featureSnapshotId}/archive
GET  /analytics/ml/datasets/feature-snapshots/{featureSnapshotId}/lifecycle-impact
POST /analytics/ml/datasets/feature-snapshots/{featureSnapshotId}/delete
POST /analytics/ml/datasets/horizon-labels
GET  /analytics/ml/datasets/horizon-labels/{jobId}
```

The preview endpoint uses backend-owned validation logic before a dataset
version is created. Dataset creation is still committed through
`POST /analytics/ml/datasets/internal-telemetry`.

## Current Pipeline Context

The current implementation has these lifecycle building blocks:

- device registry and account ownership in `device-service`;
- telemetry ingestion, time-series storage, metric discovery, readings, and
  quality flags in `telemetry-service`;
- dataset versions, validation reports, labels, feature snapshots, training
  runs, model versions, deployment policies, lifecycle actions, and audit
  behavior in `analytics-service`;
- fitting and inference runtime in private `ml-service`;
- Docker verification through `scripts/ml-failure-prediction-docker-smoke.ps1`.

The detailed target design for future workbench behavior lives in
[../../target/ml-dataset-workbench.md](../../target/ml-dataset-workbench.md).
