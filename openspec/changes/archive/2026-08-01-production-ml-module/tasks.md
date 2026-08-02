## 1. Product And Data Contracts

- [x] 1.1 Define canonical ML dataset schema for internal telemetry and external imports.
- [x] 1.2 Define dataset status, dataset version, validation report, and lineage DTOs.
- [x] 1.3 Define label schema and label source values for manual, alert-derived, maintenance-derived, and imported labels.
- [x] 1.4 Define training split strategy for time-based, device-based, and random splits.
- [x] 1.5 Define model family values for anomaly detection, degradation scoring, and forecasting.

## 2. Storage And Persistence

- [x] 2.1 Add persistence for datasets, dataset versions, imported files, validation reports, and dataset lineage.
- [x] 2.2 Add persistence for labels and label audit history.
- [x] 2.3 Add persistence for feature snapshots and feature schema hashes.
- [x] 2.4 Add persistence for training runs, run logs summary, evaluation metrics, and failure reasons.
- [x] 2.5 Add persistence for model registry metadata and artifact references.
- [x] 2.6 Add persistence for deployment policies at account, device type, and device scope.

## 3. Internal Telemetry Dataset Builder

- [x] 3.1 Add API to create a dataset version from internal telemetry using account, device, device type, metric, and time filters.
- [x] 3.2 Validate selected telemetry coverage, sampling quality, supported metrics, and retention availability.
- [x] 3.3 Attach device metadata, device type, and maintenance windows to generated dataset versions.
- [x] 3.4 Record deterministic lineage for every internal telemetry query used by a dataset version.

## 4. External Dataset Import

- [x] 4.1 Add CSV import API with file size limits, content hash, and account scoping.
- [x] 4.2 Add JSONL import API if selected for the first implementation.
- [x] 4.3 Add schema mapping for timestamp, device key, metric name/value, unit, label, and metadata columns.
- [x] 4.4 Reject rows with invalid timestamps, unsupported metric values, non-finite numbers, or credential-like fields.
- [x] 4.5 Store validation reports with accepted, rejected, warning, and mapping statistics.
- [x] 4.6 Support matching external device keys to existing devices or virtual training devices.

## 5. Feature Pipeline

- [x] 5.1 Implement deterministic feature generation for rolling statistics, baseline deviations, missing data, maintenance flags, and time features.
- [x] 5.2 Version feature pipeline configuration and feature schema hashes.
- [x] 5.3 Store feature snapshot metadata and generated artifact references.
- [x] 5.4 Add tests proving the same dataset and feature config produce the same feature hash.

## 6. Training Lifecycle

- [x] 6.1 Add async training run creation API.
- [x] 6.2 Add training worker execution for at least one anomaly detection model family.
- [x] 6.3 Add training worker execution for baseline comparison output.
- [x] 6.4 Persist training status transitions, logs summary, metrics, and artifact URI.
- [x] 6.5 Add cancellation and retry behavior for queued or running training jobs.
- [x] 6.6 Add tests for failed training runs and invalid dataset/model combinations.

## 7. Model Registry And Deployment

- [x] 7.1 Add model registry list/detail APIs with model version, status, family, algorithm, metrics, and compatibility.
- [x] 7.2 Add model approval, rejection, archive, and rollback APIs.
- [x] 7.3 Add deployment policy resolution for account default, device type policy, and device override.
- [x] 7.4 Add shadow, canary, active, and disabled deployment modes.
- [x] 7.5 Add audit events for promotion, rollback, archive, and policy changes.

## 8. Inference Integration

- [x] 8.1 Route inference to the selected trained model when an active compatible policy exists.
- [x] 8.2 Preserve baseline heuristic fallback when no compatible active model exists.
- [x] 8.3 Persist prediction traceability with model id/version, policy source, input hash, feature hash, fallback reason, confidence, signals, and explanations.
- [x] 8.4 Add shadow prediction persistence without affecting risk scoring.
- [x] 8.5 Add integration tests for trained model, shadow model, fallback model, and ML unavailable paths.

## 9. Frontend Workflows

- [x] 9.1 Add dataset list/detail views with validation and lineage details.
- [x] 9.2 Add external dataset import and schema mapping UI.
- [x] 9.3 Add label review and correction UI.
- [x] 9.4 Add training run creation, progress, logs summary, and result views.
- [x] 9.5 Add model registry list/detail comparison UI.
- [x] 9.6 Add deployment policy editor for account, device type, and device scopes.
- [x] 9.7 Add monitoring UI for drift, prediction quality, latency, errors, and fallback rate.

## 10. Observability And Operations

- [x] 10.1 Add metrics for dataset validation, feature generation, training duration, training failures, inference latency, fallback rate, and drift.
- [x] 10.2 Add structured logs for dataset creation, external import, training, model promotion, rollback, and inference failures.
- [x] 10.3 Add Docker configuration for artifact storage and asynchronous training worker runtime.
- [x] 10.4 Document operational procedures for retraining, promoting, rolling back, and deleting imported datasets.

## 11. Verification

- [x] 11.1 Run OpenSpec apply for `production-ml-module` before implementation.
- [x] 11.2 Add backend API and persistence tests for datasets, labels, training runs, model registry, and deployment policies.
- [x] 11.3 Add ML-service tests for feature generation, training, evaluation, artifact loading, and inference routing.
- [x] 11.4 Add frontend tests for dataset import, training run creation, model registry, and deployment policy workflows.
- [x] 11.5 Run Docker end-to-end verification with internal telemetry training and external dataset import.
- [x] 11.6 Run `npm run openspec:validate` before final response.
