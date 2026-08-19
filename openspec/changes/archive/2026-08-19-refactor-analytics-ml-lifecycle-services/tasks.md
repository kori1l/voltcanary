# Tasks: Refactor Analytics ML Lifecycle Services

## 1. Dataset Lifecycle

- [x] 1.1 Extract dataset preview orchestration from `MlDatasetService`.
- [x] 1.2 Extract telemetry validation and quality gate evaluation.
- [x] 1.3 Extract candidate window selection and preview timeline construction.
- [x] 1.4 Extract canonical row materialization, lineage, and hashing helpers.
- [x] 1.5 Extract dataset archive/delete impact checks and lifecycle stamping.
- [x] 1.6 Keep existing dataset controller/API behavior compatible.

## 2. Feature Snapshot Lifecycle

- [x] 2.1 Split internal telemetry feature generation from imported dataset feature generation.
- [x] 2.2 Extract feature schema/config/data hash generation.
- [x] 2.3 Extract feature snapshot lifecycle archive/delete impact checks.
- [x] 2.4 Keep feature snapshot list/generate API behavior compatible.

## 3. Model Registry And Deployment

- [x] 3.1 Split model query and DTO mapping from lifecycle commands.
- [x] 3.2 Extract approval gate and artifact metadata verification.
- [x] 3.3 Split deployment policy upsert/rollback/resolve from model lifecycle.
- [x] 3.4 Isolate demo/system model seeding behind explicit local/demo policy.
- [x] 3.5 Keep model registry and policy APIs compatible.

## 4. Analytics Recalculation

- [x] 4.1 Keep one job orchestration entry point and extract baseline/feature collection.
- [x] 4.2 Extract ML inference request construction and fallback decision logic.
- [x] 4.3 Extract prediction result persistence and traceability hashing.
- [x] 4.4 Extract risk/recommendation derivation and analytics event publication.
- [x] 4.5 Preserve current resilience when ML-service is unavailable.

## 5. Tests And Verification

- [x] 5.1 Add unit tests for extracted dataset preview, validation, materialization, and lifecycle collaborators.
- [x] 5.2 Add unit tests for extracted feature generation and feature lifecycle collaborators.
- [x] 5.3 Add unit tests for model lifecycle, policy resolution, and approval gate collaborators.
- [x] 5.4 Add targeted recalculation tests for fallback, traceability, and maintenance exclusion propagation.
- [x] 5.5 Run `cd backend; mvn -pl services/analytics-service -am test`.
- [x] 5.6 Run `npm run openspec:validate`.
