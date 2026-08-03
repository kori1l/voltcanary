# ML Failure Prediction Production Audit

This document is a working engineering audit, not an OpenSpec change. Its goal is
to describe, from a data engineering and ML operations perspective, why the
current ML mechanism is still a demo-level implementation and what has to exist
before VoltCanary can honestly support predictive failure models for equipment.

The intended reader is a data engineer or ML engineer who needs to create,
evaluate, deploy, and monitor models, not a UI user who only wants to see a risk
score.

## Executive Summary

VoltCanary already has useful ML scaffolding:

- an `ml-service` FastAPI boundary;
- analytics-side tables for datasets, dataset versions, validation reports,
  labels, feature snapshots, training runs, model versions, deployment policies,
  audit events, and prediction traceability;
- maintenance windows with `excludeFromMl`;
- a frontend ML console shell;
- baseline/rule fallback paths.

That scaffolding is valuable, but it is not yet a production ML mechanism. The
current system mostly records metadata and produces deterministic heuristic
scores. It does not yet create a reproducible training dataset, does not
materialize a feature matrix, does not train a real failure model, does not
evaluate models against actual future failures, and does not reliably route live
inference to the model selected by analytics-service.

The most important conclusion: the project currently predicts anomalies or
rule-based risk, not equipment failure. Predictive maintenance requires a
different target: "probability of confirmed failure or corrective maintenance in
the next N days", with a clear prediction horizon, event labels, feature windows,
time-based evaluation, and feedback from maintenance outcomes.

## Implementation Status As Of 2026-08-03

This audit started as a gap analysis. Since then, the following OpenSpec changes
have been completed and archived:

- `2026-08-02-close-ml-dataset-lifecycle`
- `2026-08-02-materialize-ml-feature-platform`
- `2026-08-02-unify-ml-artifacts-runtime`
- `2026-08-02-failure-outcome-labeling`
- `2026-08-02-supervised-failure-prediction`
- `2026-08-03-ml-deployment-runtime-monitoring`

Status of the original production blockers:

| Blocker | Current status | Evidence |
| --- | --- | --- |
| External import does not become a trainable dataset version | Implemented | `close-ml-dataset-lifecycle` tasks 2.1-2.5 |
| Internal telemetry dataset does not freeze training rows | Implemented | `close-ml-dataset-lifecycle` tasks 3.1-3.5 |
| Feature snapshot can have no usable rows | Implemented | `materialize-ml-feature-platform` tasks 2.1-2.5 |
| Java registry selects models Python cannot resolve | Implemented | `unify-ml-artifacts-runtime` tasks 4.1-4.4 |
| Training run succeeds without a real model artifact | Implemented | `unify-ml-artifacts-runtime` tasks 1.1-3.5 |
| Deployment modes are not fully enforced | Implemented | `ml-deployment-runtime-monitoring` tasks 1.1-2.5 |
| ML target is wrong for failure prediction | Implemented | `supervised-failure-prediction` tasks 1.1-2.5 |
| Labels do not represent real failure outcomes | Implemented | `failure-outcome-labeling` tasks 1.1-4.5 |
| Evaluation metrics are not meaningful | Implemented | `supervised-failure-prediction` tasks 3.1-3.5 |
| Rule risk leakage is built into inference input | Implemented | `supervised-failure-prediction` tasks 4.2-4.4 |
| Device type policy is not used in live recalculation | Implemented | `ml-deployment-runtime-monitoring` tasks 1.1-1.4 |
| Canary deployment is metadata only | Implemented | `ml-deployment-runtime-monitoring` tasks 2.1-2.5 |
| Demo models are created automatically | Implemented | `ml-deployment-runtime-monitoring` tasks 3.1-3.4 |
| Artifact storage is not production-ready | Implemented | `unify-ml-artifacts-runtime` tasks 1.1-1.5 and 5.1-5.4 |
| External device mapping does not verify ownership | Implemented | `close-ml-dataset-lifecycle` tasks 4.1-4.2 |
| Metric semantics and units are too loose | Implemented for ML datasets | `close-ml-dataset-lifecycle` tasks 3.2 and 4.3 |
| No data quality gates for real predictive maintenance | Implemented | `close-ml-dataset-lifecycle` tasks 1.3 and 4.4-4.5 |
| Training cancellation is not real for running work | Implemented | `unify-ml-artifacts-runtime` tasks 3.4-3.5 |
| Tests mostly prove metadata flow, not ML correctness | Implemented for the first production smoke gate | Unit and service tests exist across archived ML changes, and `docs/flows/ml-failure-prediction-docker-verification.md` defines a repeatable Docker scenario backed by `scripts/ml-failure-prediction-docker-smoke.ps1`. |

Remaining work should not re-open the already archived implementation slices.
The current hardening step is a repeatable production-readiness verification
path that proves the full lifecycle in Docker with persisted data, model
artifacts, deployment policy, live inference traceability, and monitoring
signals.

## Critical Workflow Failures In The Current Code

These are the concrete places where the current mechanism behaves like a demo
even though the UI and metadata model suggest a production workflow.

### External Import Does Not Become A Trainable Dataset Version

Expected production path:

1. upload CSV/JSONL;
2. validate rows;
3. create READY dataset version;
4. generate feature snapshot;
5. train model.

Current behavior:

1. upload CSV/JSONL;
2. create `ml_datasets`, `ml_imported_files`, and `ml_imported_metric_rows`;
3. create validation report for imported file;
4. no `ml_dataset_versions` row is created by the import service.

Impact:

- the frontend can say the file was imported;
- the dataset list is based on dataset versions, so imported data is not a
  normal selectable READY training dataset;
- feature generation requires a dataset version id, so the path is not closed.

### Internal Telemetry Dataset Does Not Freeze Training Rows

Expected production path:

1. query telemetry;
2. validate coverage;
3. write immutable dataset artifact;
4. store artifact URI/hash;
5. train from that exact artifact.

Current behavior:

1. query telemetry-service to validate coverage;
2. save dataset version metadata and lineage filter;
3. do not materialize the selected telemetry rows.

Impact:

- a dataset version is a query description, not a frozen data product;
- training cannot be reproduced if telemetry retention, correction, or query
  behavior changes;
- feature generation for internal telemetry currently produces metadata with no
  real feature rows.

### Feature Snapshot Can Have No Usable Rows

Expected production path:

1. load dataset version rows;
2. generate full feature matrix;
3. persist feature artifact;
4. train from feature artifact.

Current behavior:

- external rows produce simple rolling feature metadata and sample rows;
- internal telemetry dataset versions produce deterministic snapshot metadata,
  but no materialized feature matrix;
- the artifact URI is `db://ml_feature_snapshots/{id}`, but there is no feature
  table or object artifact containing all feature rows.

Impact:

- training has no real feature matrix;
- feature snapshot row count may not mean "rows that a model can load";
- offline/online parity cannot be verified.

### Java Registry Selects Models That Python Cannot Resolve

Expected production path:

1. analytics-service resolves deployment policy;
2. analytics-service sends `modelVersionId`;
3. ML-service loads that exact model artifact;
4. prediction result stores that exact model id/version.

Current behavior:

- analytics-service sends `modelVersionId` inside `modelPreference`;
- Python `ModelPreference` only knows `modelName`;
- Python trained-model routing looks up `modelName` in an in-memory registry;
- Java model registry and Python model registry are independent.

Impact:

- an approved/active Java model is not actually used by Python inference;
- live scoring silently falls back to heuristic behavior;
- prediction traceability can imply a selected model policy even when the model
  was not loaded.

### Training Run Succeeds Without A Real Model Artifact

Expected production path:

1. worker loads feature artifact;
2. trains model;
3. writes physical artifact;
4. verifies artifact can be loaded;
5. registers candidate model.

Current behavior:

- Java worker creates placeholder metrics;
- Java worker writes an `artifact://ml-models/.../model.json` URI string;
- no physical artifact is produced by that Java code path;
- no Python training is called from analytics-service.

Impact:

- candidate model metadata can exist without a loadable model;
- approval/deployment can operate on metadata that does not correspond to an
  inference artifact.

### Deployment Modes Are Not Fully Enforced

Expected production path:

- `DEVICE_TYPE` policies are used when no device override exists;
- `CANARY` includes only the deterministic configured percentage;
- `SHADOW` scores the candidate model and keeps active output separate.

Current behavior:

- live recalculation passes `deviceType=null` to policy resolution;
- `canaryPercent` is stored but not applied in live inference;
- because model routing falls back to baseline, shadow can store baseline-like
  output instead of candidate model output.

Impact:

- deployment policy UI looks richer than runtime behavior;
- device-type rollouts and canary experiments are not trustworthy.

## Current State

### What Exists And Should Be Preserved

The current implementation has a good service boundary direction:

- `ml-service/app/main.py` exposes FastAPI endpoints for health, models,
  inference, and training.
- `ml-service/app/services/inference_service.py` returns deterministic
  predictions instead of fixed mock values.
- `backend/services/analytics-service/.../MlDatasetService.java` creates
  internal telemetry dataset metadata and validation reports.
- `backend/services/analytics-service/.../MlExternalDatasetImportService.java`
  imports CSV/JSONL rows into account-scoped training tables.
- `backend/services/analytics-service/.../MlFeaturePipelineService.java`
  records feature snapshot metadata.
- `backend/services/analytics-service/.../MlTrainingService.java` creates
  queued training runs.
- `backend/services/analytics-service/.../MlTrainingWorker.java` moves training
  runs through status transitions and registers candidate model metadata.
- `backend/services/analytics-service/.../MlModelRegistryService.java` stores
  model versions, deployment policies, and audit events.
- `backend/services/analytics-service/.../AnalyticsRecalculationService.java`
  persists prediction traceability and keeps rule/baseline fallback behavior.

These are useful foundations. The problem is that most of the data/ML behavior
behind them is not yet real enough for a professional data engineer to operate.

### What The Current ML Service Really Does

`BaselineModel` stores per-metric `mean`, `stddev`, `min`, `max`, and `count`.
`AnomalyModel` is a thin wrapper around that baseline. Training stores those
statistics as JSON. Inference compares current metric values against those
statistics or against absolute thresholds.

This is a heuristic anomaly detector. It is not:

- a failure prediction model;
- a supervised model trained from confirmed failures;
- a time-to-failure model;
- a degradation model based on long-term trajectory;
- a forecast model;
- a calibrated probability model.

The current Python dependencies are only FastAPI, Uvicorn, Pydantic, settings,
and HTTPX. There is no `numpy`, `pandas`, `scikit-learn`, `joblib`, `pyarrow`, or
real artifact storage client. That is a strong signal that the current ML
runtime is still a skeleton.

## Production Blockers

### 1. The ML Target Is Wrong For Failure Prediction

Current outputs are `anomalyScore`, `degradationScore`, `riskLevel`,
`confidence`, signals, and explanations. These are useful product fields, but
they do not define a predictive maintenance target.

For failure prediction, the model must answer a more specific question:

- Will this device have a confirmed failure or corrective maintenance event in
  the next 7, 14, or 30 days?
- How confident are we given history length, data quality, labels, device type,
  and sensor coverage?
- Which feature changes drove the risk?
- Is the result a calibrated probability, a relative risk rank, or a heuristic
  warning?

Without a horizon, the system cannot train or evaluate correctly. An anomaly at
10:00 may be a short spike, normal use, sensor noise, or the beginning of
degradation. It is not automatically a failure.

Required change:

- Introduce a first-class `FAILURE_PREDICTION` model family.
- Add `failureRiskScore`, `failureHorizonDays`, `failureRiskBand`,
  `riskDrivers`, and `labelCoverage` to the ML contract.
- Keep `anomalyScore` and `degradationScore`, but treat them as separate
  signals, not as substitutes for failure probability.

### 2. Labels Do Not Represent Real Failure Outcomes

Current labels are:

- `NORMAL`
- `ANOMALY`
- `DEGRADATION`
- `MAINTENANCE`
- `UNKNOWN`

That is insufficient for supervised failure prediction. `MAINTENANCE` is not the
same as failure. It may be preventive cleaning, inspection, scheduled service,
firmware work, a sensor replacement, or a repair after breakdown. A model cannot
learn useful failure risk unless those outcomes are separated.

Current maintenance data also represents time windows and exclusion behavior,
not outcomes. `MaintenanceOperation` has title, description, start/end, and
exclude flags, but no structured fields for:

- failure observed;
- corrective vs preventive work;
- component replaced;
- root cause;
- technician finding;
- severity;
- device returned to normal;
- false alarm;
- unresolved issue;
- cost or downtime.

Required change:

- Add structured maintenance outcome records.
- Add label types such as `CONFIRMED_FAILURE`,
  `CORRECTIVE_MAINTENANCE`, `PREVENTIVE_MAINTENANCE`,
  `INSPECTION_NORMAL`, `FALSE_POSITIVE`, `SENSOR_FAULT`, and
  `UNRESOLVED_ANOMALY`.
- Generate training labels from outcome events using explicit rules:
  for example, all feature rows in the 14 days before a confirmed failure become
  positive examples for a 14-day horizon.
- Preserve manual labels, but make maintenance outcomes the primary supervised
  label source.

### 3. Dataset Versions Are Metadata, Not Immutable Training Data

Internal telemetry dataset creation validates telemetry coverage and writes
lineage metadata. It does not materialize the selected telemetry rows into an
immutable dataset artifact. That means a dataset version is not truly
reproducible if telemetry retention, correction, deletion, or query behavior
changes later.

External import has a more serious break: CSV/JSONL import creates
`ml_datasets`, `ml_imported_files`, and `ml_imported_metric_rows`, but does not
create a `ml_dataset_versions` row. Feature generation and training expect a
READY dataset version. Therefore the external import path is not a closed
operator workflow.

Required change:

- Every import must create or update a dataset version.
- Every READY dataset version must point to an immutable physical artifact or
  immutable row set.
- Internal telemetry datasets should be exported to object storage, preferably
  partitioned Parquet, not only represented as query filters.
- Dataset version metadata must include row count, device count, metric count,
  time range, source hashes, schema version, units, label coverage, data quality
  report id, and artifact URI.
- Dataset versions must be reproducible without re-querying mutable telemetry.

### 4. Feature Snapshots Are Not Real Feature Matrices

`MlFeaturePipelineService` currently creates feature snapshot metadata. For
external imports, it computes a small set of simple features and stores sample
rows in metadata. For internal telemetry datasets, it returns deterministic
metadata with `rowCount=0` instead of materialized feature rows.

This is not enough for model training. A data engineer needs a stable feature
matrix with:

- one row per prediction time per device per horizon;
- feature columns;
- label columns when available;
- split assignment;
- row-level lineage;
- feature schema hash;
- data quality flags;
- artifact URI.

The current feature hash is mainly a schema/config hash. It does not prove that
the same input data produced the same feature values.

Required change:

- Materialize feature snapshots as versioned artifacts.
- Include all feature rows, not just sample rows.
- Store a data hash in addition to schema hash.
- Use the same feature code for offline training and online inference.
- Feature snapshots must fail if required rows cannot be generated.

### 5. Online And Offline Features Do Not Match

Training-side features and live inference inputs are different shapes.

Current live inference sends aggregate fields from
`AnalyticsRecalculationService`:

- `metric`
- `sampleCount`
- `mean`
- `median`
- `p90`
- `min`
- `max`
- `riskScore`
- `latestValue`
- one metric-specific latest value
- maintenance excluded window ids

Training-side Python receives raw `MetricRow` rows and learns simple metric
statistics. Java feature snapshots produce rolling features only for imported
rows.

There is no shared feature contract that guarantees the model sees the same
feature columns at training and inference time. This is one of the biggest
production ML risks.

Required change:

- Define a canonical feature vector contract for each model family.
- Build an online feature generation path that produces exactly the same
  columns as the offline feature snapshot.
- Include `featureSetVersion`, `featureSchemaHash`, and `featureDataHash` in
  both training artifacts and prediction traceability.
- Reject or fallback when the active model requires features that cannot be
  generated online.

### 6. The Model Registry And ML Service Registry Are Split

Analytics-service has a model registry in PostgreSQL. Python `ml-service` has an
in-memory registry. These are not the same registry.

Analytics-service deployment policy sends `modelVersionId` inside
`modelPreference`. Python `InferenceService` routes trained model inference by
`modelName`, not by `modelVersionId`. Therefore an active model selected by the
Java registry is not actually loadable by the Python inference path through the
current contract.

Required change:

- Make `modelVersionId` the canonical cross-service model identifier.
- ML-service must load model metadata/artifacts by `modelVersionId`.
- Analytics-service must pass artifact URI, model key/version, or a stable model
  reference that ML-service can resolve.
- Remove or clearly isolate Python in-memory registry from production runtime.
- Treat Java registry as the source of deployment truth, or move the registry to
  ML-service and have analytics consume it. Do not keep two independent sources
  of truth.

### 7. Training Worker Does Not Train A Model

`MlTrainingWorker` validates that the dataset version is READY and the feature
snapshot has rows, then writes synthetic metrics and an `artifact://ml-models`
URI. It does not:

- read a feature artifact;
- call `ml-service`;
- run Python training;
- create a serialized model artifact;
- compute real validation/test metrics;
- store a model card;
- upload to MinIO/S3;
- prove that the artifact can be loaded for inference.

Required change:

- Create an actual ML worker runtime.
- The worker must read the dataset/feature artifact, train the selected
  algorithm, evaluate it, persist model artifacts, and update the registry.
- Candidate model registration must be blocked unless artifact creation and
  load-test inference succeed.
- The Java training run should either delegate to ML-service/ML-worker or the
  Python worker should own training state updates through a controlled API.

### 8. Current Evaluation Metrics Are Not Meaningful

Current metrics include `trainingRows`, `coverage`, `falsePositiveProxy`, and
for Python `validationAnomalyScore` from the last few rows. These are not model
quality metrics.

For predictive maintenance, the core metrics should be:

- precision/recall/F1 for each horizon;
- false alerts per device per month;
- average lead time before confirmed failure;
- recall at fixed false-alert budget;
- precision at top-K risk devices;
- PR-AUC for imbalanced labels;
- calibration error if output is a probability;
- coverage: what percentage of devices can be scored;
- fallback rate;
- latency and error rate.

Required change:

- Use time-based train/validation/test splits.
- Never use random split as default for time-series failure prediction.
- Compare every model against baseline/rule heuristic.
- Store evaluation reports with enough detail to reproduce the decision.
- Block model approval when minimum gates fail.

### 9. Risk Score Leakage Is Built Into The Inference Input

`AnalyticsRecalculationService` sends the rule-based `riskScore` into the ML
metrics map. Python then can use `riskScore` as an input to calculate
`degradationScore`.

This is circular: the ML output is partly derived from the rule engine output,
then stored as ML prediction traceability. For a real model, this causes
leakage and makes it hard to know whether ML is adding signal or just
repackaging deterministic rules.

Required change:

- Do not feed final rule risk score as a model feature unless explicitly using
  a stacking model with clear lineage.
- If rule signals are used, send them as separate named features with versioned
  provenance, for example `rule_power_spike_score_v1`.
- Keep `ruleRiskScore` and `mlFailureRiskScore` separate in prediction results.

### 10. Device Type Policy Is Not Used In Live Recalculation

The registry supports device type policies, and tests verify resolution order:
device, device type, account, system. But live recalculation calls
`resolvePolicy(accountId, deviceId, null)`, so `DEVICE_TYPE` policies cannot be
selected unless another path provides the type.

Required change:

- Analytics recalculation must load device type or receive it in the job/event.
- Policy resolution must include `deviceType`.
- Prediction traceability must store the resolved device type and policy source.

### 11. Canary Deployment Is Metadata Only

The system stores `CANARY` mode and `canaryPercent`, but live inference does not
appear to apply deterministic canary selection.

Required change:

- Implement deterministic canary inclusion, for example:
  `hash(accountId, deviceId, modelVersionId) % 100 < canaryPercent`.
- Store whether a prediction was canary-included or fell back.
- Expose canary metrics separately from active and shadow.

### 12. Demo Models Are Created Automatically

`MlModelRegistryService.ensureDemoModels` creates demo models when the account
has no models. That is useful for UI development but dangerous in production
because the model list can look ready even when no actual model was trained.

Required change:

- Gate demo model seeding behind an explicit local/dev profile.
- Production model registry must only show real system fallback models and real
  trained model versions.
- UI should visually distinguish `SYSTEM_HEURISTIC`, `DEMO`, `CANDIDATE`,
  `APPROVED`, and `ACTIVE`.

### 13. Artifact Storage Is Not Production-Ready

Python artifact store writes local JSON under `./models` and returns
`artifact://local/...`. Docker Compose exposes MinIO and `ML_ARTIFACT_BUCKET`,
but the Python artifact store does not use MinIO. Java worker writes synthetic
`artifact://ml-models/...` references without creating a physical artifact.

Required change:

- Use one artifact store abstraction backed by MinIO/S3 locally and in deployed
  environments.
- Store datasets, feature snapshots, trained models, preprocessors, metrics,
  model cards, and inference test fixtures.
- Store artifact hashes and sizes in the registry.
- Verify artifact existence and hash before approval and before inference.

### 14. External Device Mapping Does Not Verify Ownership

External import can parse `existingDeviceId` and set it as `matchedDeviceId`.
The code only checks that it is a UUID. It does not verify that the device
belongs to the account. This can create cross-account contamination in training
metadata.

Required change:

- Verify matched devices through device-service.
- Reject or quarantine imported rows whose mapped device does not belong to the
  importing account.
- Store mapping confidence and mapping source.

### 15. Metric Semantics And Units Are Too Loose

Current imports accept arbitrary metric names and units. Internal dataset
requests accept arbitrary metric strings. There is no strong canonical metric
registry for ML.

Required change:

- Define canonical ML metrics per device type.
- Normalize units during dataset creation.
- Store raw unit and canonical unit.
- Reject incompatible unit/metric pairs.
- Record sensor/source identity, not only metric name.
- Track metric aliases from external datasets.

### 16. No Data Quality Gates For Real Predictive Maintenance

Current validation checks sample count, non-finite values, and max sampling gap.
That is not enough.

Required gates should include:

- coverage ratio by device/metric/window;
- duplicate timestamp rate;
- out-of-order data rate;
- impossible value rate;
- unit consistency;
- sensor dropouts;
- constant-line/stuck-sensor detection;
- maintenance overlap;
- label density;
- positive event count;
- class imbalance;
- history length per device;
- device population size per type;
- timezone and sampling frequency consistency.

For failure prediction specifically, a dataset should not be READY for supervised
training unless it has enough outcome labels for the selected horizon.

### 17. Training Cancellation Is Not Real For Running Work

Analytics can mark `RUNNING` training as canceled, but there is no long-running
worker process checking cancellation while training. Once real Python training
exists, cancellation must be cooperative and reflected in the worker.

Required change:

- Add job leases and heartbeat timestamps.
- Add cancellation check points in the worker.
- Mark stale runs as failed or retryable.
- Prevent two workers from training the same run.

### 18. Tests Mostly Prove Metadata Flow, Not ML Correctness

Current tests validate state transitions, DTOs, heuristic outputs, and simple
hash determinism. They do not validate:

- closed import-to-dataset-version-to-feature-to-training flow;
- internal telemetry materialization;
- online/offline feature parity;
- real model artifact load;
- modelVersionId routing;
- device type policy in live inference;
- canary selection;
- label-derived failure horizons;
- time-based evaluation correctness;
- leakage prevention.

Required change:

- Add integration tests around the full ML lifecycle.
- Add deterministic fixture datasets with known labels and expected metrics.
- Add model artifact round-trip tests.
- Add contract tests between analytics-service and ml-service.

## What A Working Mechanism Should Look Like

### Target Workflow For A Data Engineer

A data engineer should be able to execute this workflow without hidden manual
steps:

1. Select source data: internal telemetry, external import, or mixed.
2. Inspect data quality and schema mapping.
3. Create an immutable dataset version.
4. Review label and outcome coverage.
5. Generate a feature snapshot for a specific model family and horizon.
6. Train one or more candidate models.
7. Compare candidates against baseline and current active model.
8. Review model card, metrics, feature importance, and failure examples.
9. Promote to shadow.
10. Monitor shadow performance and drift.
11. Promote to canary, then active.
12. Roll back quickly if fallback rate, errors, or false alerts rise.
13. Feed maintenance outcomes and false positives back into labels.
14. Retrain from a reproducible dataset/feature snapshot.

If any step requires database scraping, hand-copying artifact IDs, guessing
schema, or manually reconciling Java and Python registries, the mechanism is not
complete.

### Data Engineer User Stories That Must Work

These are the practical stories the mechanism should support.

#### Story: Build A Model From Internal Telemetry

As a data engineer, I select devices, metrics, device type, and time window.
The system creates an immutable dataset version, shows quality statistics,
generates feature rows, trains a model, reports metrics, and lets me deploy it
to shadow.

This story is not complete today because internal telemetry rows are not frozen
as an artifact and feature snapshots for internal telemetry do not contain a
loadable feature matrix.

#### Story: Build A Model From External Historical Data

As a data engineer, I upload historical CSV/JSONL data, map columns, validate
rows, create a dataset version, generate features, and train a model.

This story is not complete today because import does not create a dataset
version that the rest of the workflow can use.

#### Story: Compare A Candidate Against Active Model

As a data engineer, I compare a candidate model against baseline and active
model using the same validation period and metrics.

This story is not complete today because evaluation metrics are placeholders
and active Java model selection is not connected to Python model loading.

#### Story: Deploy A Device-Type Model

As a data engineer, I train a model for one device type, deploy it to that type
in shadow/canary mode, and observe performance before active rollout.

This story is not complete today because live recalculation does not pass
device type into policy resolution and canary is not enforced.

#### Story: Learn From Maintenance Outcomes

As a data engineer, I use confirmed failures, corrective maintenance, false
positives, and normal inspections to generate labels for the next training run.

This story is not complete today because maintenance records are exclusion
windows, not structured outcomes.

### Canonical Data Layers

Use explicit data layers. The names do not have to be exposed in the UI, but the
architecture should behave this way.

#### Bronze: Raw Inputs

Raw immutable source records:

- telemetry readings from telemetry-service;
- imported CSV/JSONL original files;
- maintenance windows;
- maintenance operations/outcomes;
- alerts and user feedback;
- device metadata snapshots;
- sensor metadata snapshots.

Bronze data must preserve source timestamps, ingestion timestamps, source ids,
raw units, raw metric names, and original file hashes.

#### Silver: Canonical ML Rows

Normalized ML rows:

- `accountId`
- `deviceId` or `externalDeviceKey`
- `virtualDeviceKey`
- `deviceType`
- `sensorId` or `sourceId`
- `timestamp`
- `metricName`
- `canonicalMetricName`
- `value`
- `rawUnit`
- `canonicalUnit`
- `qualityFlags`
- `maintenanceState`
- `metadata`
- `sourceLineage`

Silver rows are the right level for dataset versions. They should be immutable
for a dataset version.

#### Gold: Feature Rows

Training/inference rows:

- `entityId`: device or virtual device;
- `predictionAt`;
- `horizonDays`;
- feature columns;
- label columns;
- split assignment;
- row lineage;
- feature schema hash;
- feature data hash.

Gold rows are what the ML worker trains on.

### Dataset Version Requirements

A production dataset version should include:

- dataset id and version;
- source type;
- immutable artifact URI;
- artifact hash;
- row count;
- device count;
- metric count;
- device type distribution;
- time range;
- sampling profile;
- label coverage;
- positive event count by horizon;
- validation report;
- lineage records;
- schema version;
- owner/creator;
- creation timestamp;
- retention policy;
- status.

For internal telemetry, the dataset version cannot be only a query filter. It
must freeze the selected rows or produce an immutable export.

For external imports, import must create a dataset version or an explicit draft
version that can be validated and promoted to READY.

### Label And Outcome Model

The system needs a structured outcome model separate from raw maintenance
windows.

Suggested outcome fields:

- `outcomeId`
- `accountId`
- `deviceId`
- `operationId`
- `observedAt`
- `resolvedAt`
- `outcomeType`: `CONFIRMED_FAILURE`, `CORRECTIVE_MAINTENANCE`,
  `PREVENTIVE_MAINTENANCE`, `INSPECTION_NORMAL`, `FALSE_POSITIVE`,
  `SENSOR_FAULT`, `UNKNOWN`
- `failureMode`: optional controlled value, device-type specific
- `component`: optional controlled value
- `severity`
- `downtimeMinutes`
- `technicianNotes`
- `createdBy`
- `source`
- `confidence`

Then label generation can be deterministic:

- for `horizonDays=7`, rows in `[observedAt - 7d, observedAt)` are positive;
- rows after confirmed recovery can be normal if no later event occurs in the
  horizon;
- rows during maintenance exclusions are either dropped or marked with a
  maintenance flag, depending on model family;
- unresolved anomalies should not become positive failure labels by default.

### Feature Engineering Requirements

The first real feature set should not be deep learning. It should be robust,
explainable, and reproducible.

Recommended generic feature families:

- rolling mean, median, min, max, stddev;
- rolling quantiles;
- delta from baseline;
- robust z-score using median/MAD;
- trend/slope over 6h, 24h, 7d, 30d;
- EWMA/CUSUM drift indicators;
- runtime/idle time estimates;
- duty cycle;
- start/stop count;
- cycle duration;
- peak count and peak magnitude;
- missing data rate;
- sampling gap statistics;
- stuck sensor flags;
- voltage/current instability;
- active/reactive power ratio if available;
- time-of-day/day-of-week;
- maintenance proximity features;
- device age and metadata features when available.

Device-specific examples:

- fridge/freezer: compressor cycle duration, cycle frequency, night baseline,
  door-open correlation, internal temperature recovery time;
- HVAC/air conditioner: power per cooling/heating cycle, ambient temperature
  adjusted load, filter-cleaning outcome, duty cycle growth;
- pumps/compressors/fans: vibration trend, current increase at same load,
  pressure/flow deviation, runtime hours since maintenance;
- heaters/boilers: heating time, temperature rise rate, energy per cycle, leak
  sensor correlation.

### Minimum Model Families

A practical first production slice should include three tiers.

#### Tier 1: Robust Rules And Statistical Baseline

Purpose: always-available fallback.

Algorithms:

- robust z-score;
- EWMA/CUSUM;
- percentile thresholds;
- device-type rules.

Outputs:

- anomaly score;
- explanation;
- fallback reason;
- confidence based on history and quality.

#### Tier 2: Unsupervised Anomaly Model

Purpose: detect abnormal patterns when labels are sparse.

Algorithms:

- Isolation Forest;
- Local Outlier Factor for offline analysis;
- One-Class SVM only if data size is manageable.

Requirements:

- trained from feature matrix;
- model artifact includes preprocessing;
- threshold selected on validation data;
- false-alert proxy reported by device/month.

#### Tier 3: Supervised Failure Risk Model

Purpose: predict confirmed future failure/corrective maintenance.

Algorithms:

- logistic regression baseline;
- random forest;
- histogram gradient boosting;
- optional survival/time-to-event model later.

Requirements:

- explicit horizon;
- positive labels from outcomes;
- time-based split;
- leakage checks;
- class imbalance handling;
- calibrated probability or clearly labeled relative risk;
- feature importance/risk drivers.

Deep learning should not be a near-term priority. LSTM/GRU/autoencoders only
make sense after the project has large labeled datasets, stable feature
pipelines, and operational evaluation.

### Training Runtime Requirements

The worker needs a real runtime contract.

Inputs:

- `trainingRunId`
- `datasetVersionId`
- `featureSnapshotId`
- `modelFamily`
- `algorithm`
- `horizonDays`
- `hyperparameters`
- `evaluationConfig`

Worker steps:

1. Acquire job lease.
2. Load feature artifact.
3. Validate feature schema and row count.
4. Apply split.
5. Train preprocessing pipeline and model.
6. Evaluate against validation/test splits.
7. Generate metrics and plots/tables as artifacts.
8. Run a small inference smoke test from the saved artifact.
9. Upload model artifact and report artifacts.
10. Update training run and register candidate model.

Outputs:

- serialized model artifact;
- preprocessing artifact;
- feature schema;
- training metrics;
- evaluation metrics;
- model card;
- artifact hashes;
- logs summary;
- failure reason if failed.

### Model Registry Requirements

Model registry entries should include:

- model version id;
- model key and semantic version;
- family;
- algorithm;
- target name;
- horizon days;
- dataset version id;
- feature snapshot id;
- feature schema hash;
- artifact URI/hash;
- training code version;
- dependency lock hash;
- compatible device types;
- supported metrics;
- evaluation metrics;
- approval status;
- deployment status;
- calibration metadata;
- minimum feature requirements;
- model card URI.

No model should be approvable unless:

- artifact exists;
- artifact hash matches;
- load-test inference passes;
- evaluation metrics meet gates;
- dataset and feature snapshot are traceable;
- unsupported metrics/device types are explicit.

### Deployment And Inference Requirements

Live inference should resolve policy, generate online features, and call
ML-service with a model reference that the ML-service can actually load.

Required request fields:

- `deviceId`
- `accountId`
- `deviceType`
- `predictionAt`
- `modelVersionId`
- `deploymentMode`
- `policyId`
- `featureSetVersion`
- `featureSchemaHash`
- `features`
- `excludedIntervals`
- `fallbackAllowed`

Required response fields:

- `predictionStatus`
- `modelVersionId`
- `modelName`
- `modelVersion`
- `modelFamily`
- `failureRiskScore`
- `failureHorizonDays`
- `anomalyScore`
- `degradationScore`
- `confidence`
- `riskDrivers`
- `explanations`
- `recommendedActions`
- `featureSchemaHash`
- `inputHash`
- `fallbackReason`

Failure statuses should be explicit:

- `MODEL_ACTIVE`
- `MODEL_SHADOW`
- `BASELINE_HEURISTIC`
- `INSUFFICIENT_DATA`
- `MODEL_NOT_FOUND`
- `MODEL_INCOMPATIBLE`
- `FEATURE_SCHEMA_MISMATCH`
- `FEATURES_UNAVAILABLE`
- `ML_UNAVAILABLE`

### Monitoring Requirements

Data engineers need operational and model-quality monitoring, not only counts.

Required dashboards/metrics:

- dataset creation success/failure;
- import rejected rows by reason;
- feature generation latency/failure;
- training queue depth;
- training duration;
- training failures by reason;
- model approval/deployment events;
- inference latency by model;
- inference errors by model;
- fallback rate by model/device type;
- feature schema mismatch rate;
- missing feature rate;
- shadow vs active score difference;
- drift by feature distribution;
- false alerts per device/month;
- confirmed failure recall;
- lead time distribution;
- label delay and label coverage.

### UI Requirements For Data Engineers

The ML console should expose the real workflow:

- create internal telemetry dataset from UI;
- inspect imported dataset versions;
- see row/device/metric/time coverage;
- inspect label and outcome coverage;
- generate feature snapshots with model-family templates;
- compare feature snapshot versions;
- start training from templates;
- see real evaluation metrics;
- see model cards;
- approve only after gates pass;
- deploy shadow/canary/active;
- monitor shadow/canary results;
- review prediction errors and false positives;
- create/correct outcome labels.

The UI should not display demo models as if they were usable production models.

## Minimum Viable Production Path

This is the smallest path that would turn the current implementation from demo
to a usable first production mechanism.

### Phase 1: Close The Data Lifecycle

Deliverables:

- external import creates dataset versions;
- internal telemetry dataset materializes immutable rows;
- dataset versions have artifact URI/hash;
- READY status means data can actually be used for feature generation;
- UI can create internal datasets;
- validation reports include coverage and basic quality metrics.

Definition of done:

- A user can import a CSV, see a READY dataset version, generate features, and
  start a training run without manual database intervention.
- A user can create a dataset from internal telemetry and get a non-zero feature
  snapshot.

### Phase 2: Real Feature Snapshots

Deliverables:

- feature artifacts are full matrices, not metadata samples;
- online feature generation matches offline schema;
- feature schema/data hashes are persisted;
- feature snapshots support internal and external data.

Definition of done:

- Training and inference use the same feature names and transformations.
- A model can reject live inference with `FEATURE_SCHEMA_MISMATCH` rather than
  silently scoring wrong inputs.

### Phase 3: Real ML Worker And Artifact Store

Deliverables:

- Python ML worker with `numpy`, `pandas`, `scikit-learn`, `joblib`, and
  artifact storage;
- MinIO/S3-backed artifact store;
- real model artifacts;
- load-test inference before candidate registration;
- training status heartbeat and cancellation.

Definition of done:

- A training run produces a physical model artifact.
- The artifact can be loaded by `modelVersionId`.
- Candidate model metrics come from validation/test data, not placeholders.

### Phase 4: Failure Labels And Failure Model

Deliverables:

- structured maintenance outcomes;
- failure label generation for horizons;
- `FAILURE_PREDICTION` model family;
- baseline supervised model;
- horizon-specific metrics.

Definition of done:

- A model can predict risk of `CONFIRMED_FAILURE` or
  `CORRECTIVE_MAINTENANCE` within N days.
- Evaluation reports include precision/recall/F1, PR-AUC, false alerts per
  device/month, and lead time.

### Phase 5: Deployment Correctness

Deliverables:

- model registry source of truth is unified;
- analytics passes `modelVersionId` that ML-service resolves;
- device type policy works in live recalculation;
- deterministic canary selection works;
- shadow/canary/active metrics are separated.

Definition of done:

- An approved model selected by deployment policy is actually used by live
  inference.
- Canary affects only the intended percentage of devices.
- Shadow predictions do not affect active risk but are visible for comparison.

### Phase 6: Monitoring And Feedback Loop

Deliverables:

- model-quality dashboard;
- drift dashboard;
- false-positive review flow;
- maintenance outcome feedback;
- retraining trigger policy.

Definition of done:

- Operators can see whether a model is better than baseline.
- Operators can identify why fallback increased.
- New maintenance outcomes can be turned into labels for the next training run.

## Suggested Backlog Groups

These are not OpenSpec tasks yet. They are task-cutting candidates.

### Data Contracts

- Define canonical ML metric row.
- Define canonical feature row.
- Define maintenance outcome contract.
- Define failure prediction response contract.
- Define model artifact manifest.

### Dataset Management

- Create dataset versions for external imports.
- Materialize internal telemetry dataset artifacts.
- Add dataset artifact URI/hash.
- Add dataset quality profile.
- Add dataset version archive/retention behavior.

### Feature Platform

- Implement full feature matrix generation.
- Add online feature generation service.
- Add feature parity tests.
- Add device-type feature templates.
- Add feature data hash.

### Labeling And Outcomes

- Extend maintenance operations with structured outcomes.
- Generate labels from outcomes.
- Add false-positive/inspection-normal feedback.
- Add horizon labels.
- Add label coverage reports.

### Training Runtime

- Add Python ML dependencies.
- Add ML worker process.
- Add MinIO/S3 artifact store.
- Add sklearn baseline models.
- Add job leases, heartbeats, and cancellation.
- Add model artifact load tests.

### Evaluation

- Add time-based split implementation.
- Add horizon-specific failure metrics.
- Add baseline comparison.
- Add approval gates.
- Add model cards.

### Registry And Deployment

- Unify Java and Python model identity.
- Route inference by `modelVersionId`.
- Remove production demo models.
- Implement device type policy in recalculation.
- Implement deterministic canary.
- Verify artifact hash before activation.

### Inference

- Replace aggregate heuristic payload with model feature vector.
- Separate rule risk from ML risk.
- Add batch inference if recalculation volume grows.
- Add explicit model incompatibility statuses.
- Persist feature schema/data hash per prediction.

### Observability

- Add model-specific inference metrics.
- Add drift metrics.
- Add false-alert metrics.
- Add shadow-vs-active comparison.
- Add data freshness and feature availability metrics.

### Security And Governance

- Verify imported `existingDeviceId` ownership.
- Normalize and validate units.
- Add import quarantine for suspicious mappings.
- Add model approval permissions and audit detail.
- Avoid exposing internal artifact URIs to non-admin users.

## Non-Negotiable Acceptance Criteria

VoltCanary should not call the ML mechanism production-ready until all of these
are true:

- A READY dataset version is immutable and reproducible.
- External import and internal telemetry both produce usable dataset versions.
- Feature snapshots contain actual feature rows.
- Offline and online features use the same schema and code path.
- Training creates a real model artifact.
- Model artifacts are stored in durable object storage and hash-verified.
- Evaluation uses time-based splits and meaningful metrics.
- Failure prediction has explicit horizons and labels.
- Live inference can load the exact model selected by deployment policy.
- Device type policy and canary mode work in live recalculation.
- Rule risk and ML risk are not silently mixed.
- Prediction traceability includes model id, feature hash, input hash, fallback
  reason, and risk drivers.
- Maintenance outcomes and false positives feed future labels.
- Operators can compare active/shadow/canary performance.
- Tests cover the full lifecycle, not only metadata status transitions.

## Practical Recommendation

Do not start by adding LSTM, Prophet, or advanced forecasting libraries. That
would make the demo look more sophisticated without fixing the mechanism.

The correct first production move is data plumbing:

1. make dataset versions real and immutable;
2. make feature snapshots real and shared by training/inference;
3. make model artifacts real and loadable by `modelVersionId`;
4. add structured failure/maintenance outcomes;
5. train a simple, explainable sklearn model;
6. evaluate it honestly against future outcome labels;
7. deploy only through shadow and canary with fallback.

Once that is working, more advanced model families can be added without
rewriting the platform.

## Implementation Boundary By Service

This section maps the required work to the current repository structure so later
tasks can be cut cleanly.

### analytics-service

Primary responsibility:

- dataset version ownership;
- validation reports;
- feature snapshot metadata;
- training run state;
- model registry;
- deployment policy;
- prediction traceability;
- fallback behavior.

Required changes:

- fix external import to create dataset versions;
- materialize internal telemetry dataset artifacts;
- store dataset artifact URI/hash;
- create or delegate full feature snapshot generation;
- call or coordinate real ML worker training;
- route inference by `modelVersionId`;
- include device type in policy resolution;
- implement deterministic canary;
- separate rule risk from ML risk;
- persist failure prediction fields;
- add full lifecycle integration tests.

### ml-service

Primary responsibility:

- real feature transformations if Python owns feature generation;
- model training;
- model artifact serialization;
- model artifact loading;
- inference;
- model-family-specific evaluation.

Required changes:

- add production data science dependencies;
- add artifact store backed by MinIO/S3;
- add model loading by `modelVersionId`;
- replace in-memory registry for production use;
- implement sklearn pipelines;
- implement failure prediction model family;
- implement explicit incompatible-feature statuses;
- add artifact round-trip tests.

### device-service

Primary responsibility:

- devices;
- sensors;
- maintenance windows;
- maintenance operations.

Required changes:

- add structured maintenance outcomes;
- expose outcome events or internal APIs for analytics-service;
- verify external import device ownership when matching `existingDeviceId`;
- expose device type and relevant metadata to analytics recalculation.

### telemetry-service

Primary responsibility:

- telemetry storage and retrieval.

Required changes:

- support stable export for dataset materialization;
- expose enough source metadata for lineage;
- support large historical extraction without chart-oriented API limitations;
- preserve canonical timestamp ordering and quality flags.

### frontend

Primary responsibility:

- operator/data-engineer workflow.

Required changes:

- add UI for internal telemetry dataset creation;
- make external import produce and show dataset versions;
- show dataset quality profiles;
- show feature snapshot row counts and hashes;
- show model cards and real evaluation metrics;
- distinguish demo/heuristic/trained models;
- show shadow/canary/active comparison.

### deploy

Primary responsibility:

- runtime wiring.

Required changes:

- configure ML artifact bucket in code, not only environment variables;
- add persistent model artifact storage;
- add worker process/service if training is separated from API;
- add health checks that verify artifact store connectivity;
- add observability dashboards for ML lifecycle.

## Recommended First Task Cut

If the next work needs to be split into a small number of focused OpenSpec
changes later, the cleanest cuts are:

1. **Close Dataset Versions**
   Fix external import and internal telemetry materialization so READY dataset
   versions are real immutable data products.

2. **Materialize Feature Snapshots**
   Generate loadable feature matrices for internal and external datasets, with
   schema/data hashes and online/offline parity tests.

3. **Unify Model Artifact And Registry Flow**
   Make training produce real artifacts and make inference load by
   `modelVersionId`.

4. **Add Failure Outcomes And Horizon Labels**
   Extend maintenance data into outcome labels and generate supervised failure
   targets.

5. **Implement First Real Failure Model**
   Add sklearn training/evaluation for `FAILURE_PREDICTION` with honest
   time-based metrics.

6. **Make Deployment Modes Runtime-True**
   Fix device-type resolution, canary selection, shadow comparison, and
   monitoring.
