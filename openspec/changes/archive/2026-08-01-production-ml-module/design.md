## Context

The current ML-service is a synchronous FastAPI application that returns a
deterministic baseline heuristic prediction. Analytics-service persists
prediction metadata and can fall back when ML is unavailable. This is useful for
the UI, but it is not enough for production ML because there is no managed
training data, no artifact store, no model registry beyond a hard-coded model,
and no retraining workflow.

VoltCanary already stores telemetry and device metadata. The production ML
module should use those records as first-class training data and also support
external datasets supplied by operators or offline jobs.

## Goals / Non-Goals

**Goals:**

- Make stored telemetry reusable for model training without direct database
  scraping by users.
- Support external dataset import with validation, schema mapping, lineage, and
  quality reporting.
- Track dataset versions and immutable feature snapshots used by each training
  run.
- Train and evaluate multiple model families for anomaly detection,
  degradation scoring, and short-horizon forecasting.
- Store model artifacts and metadata so a model version can be reproduced,
  compared, promoted, rolled back, or archived.
- Support model selection policies at account, device type, and device scope.
- Keep deterministic rules and baseline heuristic as safe fallback paths.
- Provide operator UI workflows for dataset preparation, training, model
  approval, deployment, and monitoring.

**Non-Goals:**

- Do not replace telemetry ingestion with ML-specific ingestion.
- Do not require a trained model before the product can calculate risk.
- Do not allow arbitrary executable model code uploads in the first iteration.
- Do not make training synchronous with normal user requests.
- Do not expose raw internal database credentials or internal service URLs to
  users.

## Architecture

### Data Sources

The ML module SHALL support two sources of training data:

- Internal telemetry exported through service APIs using account/device/time
  filters.
- External datasets imported as CSV or JSONL with explicit schema mapping.

Imported external data SHALL be normalized into the same canonical metric model
used by internal telemetry:

- `deviceId` or external device key;
- timestamp;
- metric name;
- numeric value;
- optional unit;
- optional label fields;
- optional metadata.

Each imported dataset SHALL store provenance: uploader, original filename,
content hash, source type, mapping version, validation report, and import time.

### Dataset Versions

A dataset version is immutable after it is marked ready. It references source
telemetry windows, imported files, label sets, feature configuration, and split
strategy. Training runs SHALL reference a dataset version rather than ad hoc
queries.

Dataset status values:

- `DRAFT`
- `VALIDATING`
- `READY`
- `FAILED`
- `ARCHIVED`

### Labels

Labels SHALL be optional but supported for supervised evaluation. Labels can
come from manual UI review, alert outcomes, maintenance outcomes, or external
dataset columns.

Initial label types:

- `NORMAL`
- `ANOMALY`
- `DEGRADATION`
- `MAINTENANCE`
- `UNKNOWN`

The system SHALL keep unlabeled data usable for unsupervised models and baseline
heuristics.

### Feature Pipeline

Feature snapshots SHALL be deterministic and versioned. Inputs include
telemetry, device type, device metadata, maintenance windows, labels, and
dataset mappings.

Initial feature families:

- rolling mean/min/max/stddev for configured windows;
- baseline deviation ratios;
- voltage/current/power threshold features;
- missing data and sampling quality features;
- maintenance overlap flags;
- day-of-week and hour-of-day features;
- device type and metric schema identifiers.

Feature pipeline outputs SHALL be stored with:

- feature set version;
- dataset version;
- code/config version;
- row count;
- rejected row count;
- feature schema hash;
- generated artifact URI or table reference.

### Training

Training SHALL be asynchronous. A training run moves through:

- `QUEUED`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `CANCELED`

Initial model families:

- anomaly detection: isolation forest or equivalent unsupervised detector;
- degradation scoring: gradient/tree or statistical trend model;
- forecasting: short-horizon baseline/seasonal model.

The first implementation MAY use deterministic Python libraries and local
artifact storage, but the API must not assume a single library or runtime.

Training run metadata SHALL include:

- dataset version id;
- feature snapshot id;
- model family;
- algorithm;
- hyperparameters;
- metrics;
- artifact URI;
- logs summary;
- failure reason;
- created by;
- started/completed timestamps.

### Evaluation

Each successful training run SHALL produce evaluation metrics appropriate for
the model family:

- anomaly: precision/recall/F1 when labels exist, false positive proxy when
  unlabeled;
- degradation: MAE/RMSE or trend classification metrics;
- forecast: MAE/RMSE/MAPE;
- operational: inference latency estimate, coverage, fallback conditions.

The UI SHALL show model comparison against the current active model and the
baseline heuristic.

### Model Registry

The registry SHALL manage immutable model versions and mutable deployment
status. Model status values:

- `CANDIDATE`
- `APPROVED`
- `ACTIVE`
- `REJECTED`
- `ARCHIVED`

Model metadata SHALL include:

- model id and version;
- family and algorithm;
- compatible device types and metrics;
- dataset version id;
- feature schema hash;
- artifact URI;
- evaluation metrics;
- approval metadata;
- deployment policy references.

Artifacts SHALL be stored outside the relational database, with metadata and
hashes stored in the database. MinIO is acceptable for local and pre-production
deployments.

### Deployment Policy

Policy resolution order:

1. device override;
2. device type policy;
3. account default;
4. system default;
5. baseline heuristic fallback.

Deployment modes:

- `SHADOW`: run model and record predictions without affecting risk.
- `CANARY`: apply model to selected devices or percentage of devices.
- `ACTIVE`: use model output in analytics.
- `DISABLED`: never call trained model.

Every inference result SHALL persist model id/version, input hash, feature
snapshot hash when available, policy source, fallback reason, confidence,
signals, explanations, and recommended actions.

### UI

The frontend SHALL provide:

- dataset list and detail views;
- external dataset import with schema mapping and validation results;
- label review and correction workflow;
- training run creation and progress;
- model registry list and model detail comparison;
- deployment policy editor for account, device type, and device;
- prediction monitoring with drift, failure rate, latency, and fallback rate.

### Security And Governance

- External dataset imports SHALL be scoped to account ownership.
- Imported files SHALL be size-limited and content-hashed.
- Dataset mappings SHALL reject credential-like fields.
- Training and deployment actions SHALL require write/admin permission.
- Model promotion, rollback, and archive actions SHALL be audit logged.
- Public API errors SHALL not expose internal storage URIs, stack traces, or
  service tokens.

## Decisions

### Keep baseline heuristic as permanent fallback

Trained models can fail, drift, or be unavailable. Analytics must continue to
produce deterministic risk output using rules and baseline heuristics.

### Version data, features, and models separately

Datasets, feature snapshots, and model artifacts change at different cadences.
Separate versioning makes training reproducible and prevents silent model drift.

### Treat external data as imported training data, not live telemetry

External datasets may have different quality and provenance. They should be
validated and mapped before training, not mixed directly into live telemetry
tables.

### Use shadow mode before active rollout

Shadow deployment lets operators compare predictions against current analytics
before a trained model affects risk scoring.

## Risks / Trade-offs

- Training infrastructure can become large quickly. The first version should
  support local Docker with MinIO artifacts and async workers, then leave room
  for Kubernetes/batch runners.
- External datasets can be noisy. Validation, mapping, and quality reporting are
  required before training.
- Too many model knobs can confuse users. The UI should expose policy choices
  and training templates first, then advanced hyperparameters behind an expert
  mode.
- Labels may be sparse. The system must support unsupervised training and still
  report weaker confidence when labels are unavailable.

## Open Questions

- Which external file formats are required first: CSV only, or CSV plus JSONL?
- What is the minimum training window per device type?
- Should training run inside `ml-service`, a separate `ml-worker`, or both?
- Which model families are mandatory for the first production slice?
- Should imported external devices be matched to existing devices, virtual
  training devices, or both?

