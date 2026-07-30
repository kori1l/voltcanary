## Why

VoltCanary currently exposes ML settings and a Python inference service, but the
ML behavior is limited to a baseline heuristic. Operators can ingest telemetry,
yet there is no production-grade mechanism for turning stored telemetry or
external historical datasets into trained anomaly, degradation, or forecast
models.

The product needs an ML module that treats data as a managed asset: internal
telemetry, externally imported files, labels, feature snapshots, training runs,
model artifacts, evaluations, deployment decisions, and inference traces must be
auditable and reproducible.

## What Changes

- Define a managed ML data layer for selecting internal telemetry windows and
  importing external datasets.
- Add dataset versioning, validation, lineage, labels, and split strategy for
  train/validation/test usage.
- Add a feature pipeline that builds repeatable feature snapshots from telemetry,
  maintenance windows, device metadata, labels, and external data.
- Add training orchestration for anomaly detection, degradation scoring, and
  forecast models.
- Add model registry support for artifact storage, versions, metrics, status,
  approval, rollback, and compatibility with device types and metric schemas.
- Add controlled deployment policies for account, device type, and device
  overrides, including safe fallback to deterministic rules and baseline
  heuristics.
- Add UI workflows for data preparation, training runs, model comparison,
  deployment, and monitoring.
- Add observability for drift, inference latency, model failures, prediction
  quality, fallback rate, and data quality.

## Capabilities

### New Capabilities

- `ml-data-management`: Internal and external datasets, labels, validation,
  lineage, retention, and training splits.
- `ml-training-lifecycle`: Training orchestration, evaluation, model registry,
  deployment, rollback, and monitoring.

### Modified Capabilities

- `ml-predictive-analytics`: Inference must support selected trained models,
  baseline heuristic fallback, explainability, and traceable model versions.
- `frontend-ml-service-settings`: Settings must evolve from simple preferences
  into model policy, dataset, training, deployment, and monitoring workflows.

## Impact

- `ml-service/**`: training orchestration, feature pipeline, model registry
  client, inference router, model adapters, data validation.
- `backend/services/analytics-service/**`: training dataset selection,
  prediction traceability, model policy resolution, model deployment state.
- `backend/services/device-service/**`: device type and metadata context for
  dataset selection and model compatibility.
- `backend/services/telemetry-service/**`: historical telemetry export APIs for
  feature generation.
- `frontend/src/**`: ML datasets, training, model registry, deployment, and
  monitoring UI.
- `docs/api/**`: ML data, training, registry, inference, and monitoring
  contracts.
- `deploy/**` and `docker-compose.yml`: artifact storage, optional workers, and
  production-ready ML runtime configuration.

