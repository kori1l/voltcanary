# ML API

Machine learning inference and model metadata endpoints.

## POST /inference

Runs deterministic predictive scoring for one device telemetry snapshot or
aggregate feature set. Existing response fields remain present; new fields are
additive.

Request:

```json
{
  "deviceId": "a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16",
  "timestamp": "2026-07-10T10:15:00Z",
  "metrics": {
    "activePowerW": 850.0,
    "voltage": 230.0,
    "current": 5.1,
    "energyGrowth": 1.0
  },
  "baseline": {
    "metric": "activePowerW",
    "mean": 800.0,
    "median": 790.0,
    "p90": 910.0,
    "min": 700.0,
    "max": 950.0,
    "sampleCount": 24
  },
  "recentWindow": {
    "sampleCount": 24,
    "excludedSampleCount": 0,
    "windowStart": "2026-07-09T10:15:00Z",
    "windowEnd": "2026-07-10T10:15:00Z"
  },
  "excludedIntervals": [],
  "modelPreference": {
    "allowBaselineHeuristic": true,
    "predictionMode": "MODEL_OR_BASELINE_HEURISTIC",
    "deploymentMode": "ACTIVE",
    "policySource": "DEVICE",
    "policyId": "6db9a07f-9f03-4ba2-9c54-92bf641ef740",
    "modelVersionId": "296cb424-2b9f-43e6-b6da-fcd175d7f469"
  }
}
```

Response:

```json
{
  "deviceId": "a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16",
  "modelName": "voltcanary-baseline-heuristic",
  "modelVersion": "baseline-heuristic-v1",
  "modelType": "baseline_heuristic",
  "modelStatus": "READY_HEURISTIC",
  "predictionStatus": "BASELINE_HEURISTIC",
  "anomalyScore": 0.05,
  "degradationScore": 0.035,
  "riskLevel": "LOW",
  "confidence": 0.82,
  "signals": [
    {
      "metric": "telemetry",
      "score": 0.05,
      "severity": "LOW",
      "explanation": "Metrics activePowerW, current, energyGrowth, voltage are within expected heuristic ranges."
    }
  ],
  "explanations": [
    "Metrics activePowerW, current, energyGrowth, voltage are within expected heuristic ranges."
  ],
  "recommendedActions": ["Continue normal monitoring."],
  "inputHash": "sha256..."
}
```

`predictionStatus` values:

- `MODEL`: trained model output.
- `BASELINE_HEURISTIC`: deterministic heuristic scoring.
- `FALLBACK`: emergency fallback output.
- `INSUFFICIENT_DATA`: request had no useful numeric telemetry.

Validation and failure responses:

- Empty `metrics` returns `200` with `predictionStatus=INSUFFICIENT_DATA`.
- Invalid JSON, missing required fields, or non-finite numeric metric values
  return `400`.
- Runtime failures return standard service errors; analytics-service treats ML
  unavailability as a degraded dependency and continues rule-based analytics.

`modelPreference` is owned by analytics-service. It passes the resolved
deployment mode, policy source, policy id, selected model version id, prediction
mode, and whether the ML-service may fall back to baseline heuristic scoring.

## GET /models

Returns active model registry metadata:

```json
[
  {
    "name": "voltcanary-baseline-heuristic",
    "version": "baseline-heuristic-v1",
    "status": "READY_HEURISTIC",
    "modelType": "baseline_heuristic"
  }
]
```

`status` distinguishes heuristic readiness from future trained model readiness
so operators and analytics-service can audit which predictive boundary produced
a result.

## Production ML metadata contracts

The production ML module treats training data, features, training runs, model
versions, and deployment policies as versioned metadata. Initial storage lives
in analytics-service; ML-service can later consume these contracts through
service APIs without scraping databases directly.

Canonical metric rows include `accountId`, `deviceId` or `externalDeviceKey`,
`timestamp`, `metricName`, numeric `value`, optional `unit`, optional `label`,
and optional string metadata.

Dataset lifecycle:

- `DRAFT`
- `VALIDATING`
- `READY`
- `FAILED`
- `ARCHIVED`

Dataset source types:

- `INTERNAL_TELEMETRY`
- `EXTERNAL_IMPORT`
- `MIXED`

Label values:

- `NORMAL`
- `ANOMALY`
- `DEGRADATION`
- `MAINTENANCE`
- `UNKNOWN`

Label sources:

- `MANUAL`
- `ALERT_OUTCOME`
- `MAINTENANCE_OUTCOME`
- `IMPORTED`

Training split strategies:

- `TIME_BASED`
- `DEVICE_BASED`
- `RANDOM`

Model families:

- `ANOMALY_DETECTION`
- `DEGRADATION_SCORING`
- `FORECASTING`

Training run statuses:

- `QUEUED`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `CANCELED`

Model registry statuses:

- `CANDIDATE`
- `APPROVED`
- `ACTIVE`
- `REJECTED`
- `ARCHIVED`

Deployment scopes resolve in `DEVICE`, `DEVICE_TYPE`, `ACCOUNT`, `SYSTEM`,
then baseline fallback order. Deployment modes are `SHADOW`, `CANARY`,
`ACTIVE`, and `DISABLED`.

## POST /analytics/ml/datasets/internal-telemetry

Creates a dataset version from stored telemetry. The frontend calls this through
analytics-service; analytics-service authorizes each selected device and reads
historical measurements from telemetry-service.

Request:

```json
{
  "name": "Washer July active power",
  "description": "Internal telemetry for anomaly training",
  "deviceIds": ["a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16"],
  "deviceType": "washer",
  "deviceMetadata": {
    "firmware": "2.4.1"
  },
  "metrics": ["activePowerW"],
  "from": "2026-07-01T00:00:00Z",
  "to": "2026-07-31T23:59:59Z",
  "splitStrategy": "TIME_BASED",
  "splitConfigJson": "{\"train\":0.7,\"validation\":0.15,\"test\":0.15}",
  "featureConfigVersion": "feature-config-v1",
  "minSamplesPerMetric": 24,
  "maxSamplingGapSeconds": 21600
}
```

Response:

```json
{
  "datasetId": "3e509cd3-dc0a-4904-af62-bfb877d9e4b7",
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "accountId": "f1a521bd-e272-47c2-b995-05fcf5ce51d5",
  "version": 1,
  "status": "READY",
  "splitStrategy": "TIME_BASED",
  "acceptedRows": 744,
  "rejectedRows": 0,
  "warningCount": 1,
  "validationMessages": [
    "Device a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16 overlaps 1 maintenance windows."
  ],
  "createdAt": "2026-07-30T00:00:00Z",
  "readyAt": "2026-07-30T00:00:00Z"
}
```

Validation stores a report and deterministic lineage for the dataset version.
Insufficient samples, unsupported or unavailable metrics, and non-finite values
produce `FAILED` dataset versions with rejected row counts and reason summaries.
Telemetry-service unavailability returns a dependency error and does not create
a partially validated dataset.

## POST /analytics/ml/datasets/external-csv

Imports an account-scoped external CSV file without writing raw rows into live
telemetry tables. The request uses `multipart/form-data`.

Parts:

- `file`: CSV file, maximum 10 MiB.
- `mappingJson`: JSON schema mapping.
- `datasetName`: optional dataset display name.
- `description`: optional dataset description.

Mapping:

```json
{
  "timestampColumn": "ts",
  "deviceKeyColumn": "device",
  "metricNameColumn": "metric",
  "metricValueColumn": "value",
  "unitColumn": "unit",
  "labelColumn": "label",
  "existingDeviceIdColumn": "existingDeviceId",
  "deviceTypeColumn": "deviceType",
  "metadataColumns": ["firmware", "batch"],
  "mappingVersion": "csv-mapping-v1",
  "allowPartialAcceptance": true
}
```

Response:

```json
{
  "datasetId": "3e509cd3-dc0a-4904-af62-bfb877d9e4b7",
  "importedFileId": "810a00cc-9853-4734-ac4b-07e7ab68ca0d",
  "validationReportId": "e93d690f-f2f0-4e48-9d93-9ff45c68dcf1",
  "contentHash": "sha256:...",
  "acceptedRows": 1200,
  "rejectedRows": 3,
  "warningCount": 0,
  "validationMessages": ["Row 82: INVALID_TIMESTAMP"],
  "importedAt": "2026-07-30T00:00:00Z"
}
```

Validation rejects rows with invalid timestamps, missing device keys, missing
metric names, invalid or non-finite metric values, and invalid label values.
Mappings reject credential-like field names such as password, secret, token,
credential, and api_key. Accepted rows are stored as imported training rows
linked to the imported file and validation report.

If `existingDeviceIdColumn` is present and a row value is a UUID, the imported
row is linked to that existing device id. Every accepted row also receives a
stable `virtualDeviceKey` derived from the account id and external device key,
so imported-only devices remain usable for training. Device mappings are stored
per imported file and external device key.

## POST /analytics/ml/datasets/external-jsonl

Imports an account-scoped JSONL file with the same `multipart/form-data` parts
and mapping fields as the CSV endpoint. Each line must be a JSON object whose
top-level fields match the mapping names.

Example JSONL row:

```json
{"ts":"2026-07-01T00:00:00Z","device":"washer-1","existingDeviceId":"a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16","deviceType":"washer","metric":"activePowerW","value":123.4,"unit":"W","label":"NORMAL","firmware":"2.4.1"}
```

The response shape matches `POST /analytics/ml/datasets/external-csv`.
Invalid JSON lines are rejected with `INVALID_JSONL`; valid lines can still be
accepted when `allowPartialAcceptance` is true.

## POST /analytics/ml/datasets/feature-snapshots

Generates a deterministic feature snapshot for a `READY` dataset version.

Request:

```json
{
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "featureSetVersion": "feature-set-v1",
  "codeConfigVersion": "analytics-feature-pipeline-v1",
  "rollingWindowSize": 6,
  "missingGapSeconds": 21600,
  "enabledFeatureFamilies": [
    "rolling_stats",
    "baseline_deviation",
    "missing_data",
    "time",
    "label"
  ]
}
```

Response:

```json
{
  "id": "296cb424-2b9f-43e6-b6da-fcd175d7f469",
  "accountId": "f1a521bd-e272-47c2-b995-05fcf5ce51d5",
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "featureSetVersion": "feature-set-v1",
  "codeConfigVersion": "analytics-feature-pipeline-v1",
  "featureSchemaHash": "sha256:...",
  "rowCount": 1200,
  "rejectedRowCount": 0,
  "artifactUri": "db://ml_feature_snapshots/296cb424-2b9f-43e6-b6da-fcd175d7f469",
  "createdAt": "2026-07-30T00:00:00Z"
}
```

The initial pipeline computes rolling mean/min/max/stddev, baseline deviation
ratio, missing-gap indicators, hour/day time features, and labels for accepted
external rows. Internal telemetry dataset versions produce deterministic
snapshot metadata from their lineage until raw feature materialization is added.
Repeating generation with the same dataset and feature config produces the same
feature schema hash.

## POST /analytics/ml/training-runs

Creates an asynchronous training run. The first production slice supports
`ANOMALY_DETECTION` with a deterministic baseline statistical algorithm.

Request:

```json
{
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "featureSnapshotId": "296cb424-2b9f-43e6-b6da-fcd175d7f469",
  "modelFamily": "ANOMALY_DETECTION",
  "algorithm": "baseline-statistical-anomaly-v1",
  "hyperparametersJson": "{}"
}
```

Response status is initially `QUEUED`. A worker moves runs through `RUNNING`
to `SUCCEEDED` or `FAILED`. Successful runs store evaluation metrics, an
artifact URI, log summary, timestamps, and register a `CANDIDATE` model version.
Failure reasons are sanitized and stored without stack traces.

## GET /analytics/ml/training-runs

Lists recent training runs for the current account.

## POST /analytics/ml/training-runs/{runId}/cancel

Cancels a `QUEUED` or `RUNNING` training run and stores completion metadata.

## POST /analytics/ml/training-runs/{runId}/retry

Creates a new `QUEUED` training run from a `FAILED` or `CANCELED` run using the
same dataset version, feature snapshot, model family, algorithm, and
hyperparameters.

## GET /analytics/ml/models

Lists account model versions with model key, version, family, algorithm,
status, dataset version, feature snapshot, artifact hash/URI, compatibility,
and evaluation metrics.

## GET /analytics/ml/models/{modelVersionId}

Returns one model version detail.

## POST /analytics/ml/models/{modelVersionId}/approve

Moves a `CANDIDATE` or `REJECTED` model to `APPROVED`, records approval
metadata, and writes an audit event.

## POST /analytics/ml/models/{modelVersionId}/reject

Moves a non-active model to `REJECTED` and writes an audit event.

## POST /analytics/ml/models/{modelVersionId}/archive

Moves a non-active model to `ARCHIVED` and writes an audit event.

## GET /analytics/ml/deployment-policies

Lists deployment policies for the current account.

## POST /analytics/ml/deployment-policies

Creates or updates a deployment policy.

Request:

```json
{
  "scope": "ACCOUNT",
  "deviceId": null,
  "deviceType": null,
  "modelVersionId": "296cb424-2b9f-43e6-b6da-fcd175d7f469",
  "mode": "ACTIVE",
  "canaryPercent": null,
  "fallbackEnabled": true,
  "reason": "Approved after shadow comparison"
}
```

Scopes are `SYSTEM`, `ACCOUNT`, `DEVICE_TYPE`, and `DEVICE`. Modes are
`SHADOW`, `CANARY`, `ACTIVE`, and `DISABLED`. Enabled policies require an
approved or active model. `CANARY` requires `canaryPercent` from 0 to 100.
Policy changes are audit logged; `ACTIVE` policy creation marks the selected
model version `ACTIVE`.

## POST /analytics/ml/deployment-policies/rollback

Disables the selected policy scope, clears its model version, enables fallback,
and writes a rollback audit event.

Query parameters:

- `scope`
- `deviceId`
- `deviceType`
- `reason`

## GET /analytics/ml/deployment-policies/resolve

Resolves the effective model policy in order: `DEVICE`, `DEVICE_TYPE`,
`ACCOUNT`, `SYSTEM`, then baseline heuristic fallback.

## Prediction traceability

Analytics-service persists prediction traceability on every recalculation:
model version id, policy source, policy id, deployment mode, input hash, feature
schema hash when available, fallback reason, confidence, signals, explanations,
and recommended actions. `SHADOW` deployments create an additional shadow
prediction record with `shadowPrediction=true`; latest prediction reads exclude
shadow records so shadow scoring does not change active risk or user-facing
recommendations.

## Frontend status boundary

The frontend does not call ML-service directly. It reads the latest persisted prediction through analytics-service. Model name, version, type, readiness status, prediction status, confidence, signals, explanations, recommended actions, input hash, model id, policy source, fallback reason, and shadow marker are read-only traceability fields. Missing or unavailable ML metadata is represented with controlled status values; internal endpoints and errors are not exposed.
