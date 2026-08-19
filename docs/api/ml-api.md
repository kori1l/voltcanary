# ML API

Machine learning inference and model metadata endpoints.

## Internal ML-service boundary

The frontend never calls ML-service directly. Analytics-service is the trusted
caller for ML-service execution and metadata endpoints.

When `ML_INTERNAL_AUTH_REQUIRED=true`, every non-probe ML-service endpoint
requires:

- `X-Source-Service: analytics-service`
- `X-Internal-Service-Token: <configured internal token>`

This boundary applies to `/inference`, `/train`, `/runs/{runId}`, `/models`,
and the compatibility aliases under `/ml/*`. `GET /health` and
`GET /health/artifacts` stay unauthenticated for Kubernetes probes.

Sanitized boundary failures return:

- `401` with `missing_internal_service_token` when the token header is absent.
- `403` with `invalid_internal_service_token` when the token does not match.
- `403` with `source_service_forbidden` when the source service is not in the
  configured allowlist.
- `413` with `request_body_too_large` when `/train`, `/inference`, or their
  `/ml/*` aliases exceed `ML_MAX_REQUEST_BYTES`.
- `400` for invalid JSON or request shape errors, without stack traces,
  secrets, raw request input, or internal artifact paths.

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
    "modelVersionId": "296cb424-2b9f-43e6-b6da-fcd175d7f469",
    "manifestUri": "s3://model-artifacts/model-296cb424/manifest.json",
    "artifactUri": "s3://model-artifacts/model-296cb424/model.joblib",
    "artifactHash": "sha256:...",
    "artifactSizeBytes": 204800,
    "requiredFeatureSchemaHash": "sha256:..."
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

- `TRAINED_MODEL`: trained model output loaded from a model artifact bundle.
- `BASELINE_HEURISTIC`: deterministic heuristic scoring.
- `FALLBACK`: emergency fallback output.
- `INSUFFICIENT_DATA`: request had no useful numeric telemetry.
- `MODEL_UNAVAILABLE`: selected model artifact cannot be loaded or verified.
- `MODEL_INCOMPATIBLE`: selected model cannot score the request metrics.
- `FEATURES_UNAVAILABLE`: model-only mode required online features that were not generated.
- `FEATURE_SCHEMA_MISMATCH`: online feature schema does not match the selected model.
- `INSUFFICIENT_LABELS`: selected failure model artifact is not servable because label approval gates failed.

Validation and failure responses:

- Empty `metrics` returns `200` with `predictionStatus=INSUFFICIENT_DATA`.
- Invalid JSON, missing required fields, or non-finite numeric metric values
  return sanitized `400` responses.
- Oversized execution requests return `413` before model scoring or training
  code runs.
- Runtime failures return standard service errors; analytics-service treats ML
  unavailability as a degraded dependency and continues rule-based analytics.

`modelPreference` is owned by analytics-service. It passes the resolved
deployment mode, policy source, policy id, selected model version id, prediction
mode, artifact manifest URI/hash metadata, and whether the ML-service may fall
back to baseline heuristic scoring. Production trained-model requests must pass
artifact metadata from analytics-service model registry; Python in-memory model
registry state is only a compatibility/local path.

## GET /health/artifacts

Checks ML-service artifact store connectivity. With `ML_ARTIFACT_STORE=s3`, the
service verifies access to `ML_ARTIFACT_BUCKET` using the configured
MinIO/S3 credentials. Kubernetes readiness probes call this endpoint. In
production-like runtime profiles, `ML_ARTIFACT_STORE=local` is unhealthy
because the source tree is not a durable artifact registry.

Healthy response:

```json
{
  "service": "ml-service",
  "status": "ok",
  "provider": "s3",
  "bucket": "model-artifacts"
}
```

Unhealthy responses return `503` and never include secrets or stack traces.
Known `code` values:

- `local_artifact_store_forbidden`: production-like runtime is configured with
  `ML_ARTIFACT_STORE=local`.
- `s3_credentials_missing`: MinIO/S3 endpoint credentials are not configured.
- `artifact_store_unavailable`: the configured bucket cannot be reached or the
  credentials cannot access it.
- `unsupported_artifact_store`: `ML_ARTIFACT_STORE` names an unsupported
  provider.

## GET /models

Returns active model registry metadata. This endpoint is internal and requires
the ML-service boundary headers when `ML_INTERNAL_AUTH_REQUIRED=true`:

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

## POST /train

Starts a model training run from an already materialized dataset payload. This
endpoint is internal, requires the ML-service boundary headers when
`ML_INTERNAL_AUTH_REQUIRED=true`, and is size-limited by
`ML_MAX_REQUEST_BYTES`.

Request fields:

- `trainingRunId`: analytics-owned durable training run id. When omitted,
  ML-service generates a local compatibility id.
- `idempotencyKey`: stable retry key for the same analytics training run.
  Reusing the same key with identical request metadata returns the existing
  training response when ML-service has already recorded one.
- `artifactBundleId`: deterministic final artifact bundle id or prefix.
- `datasetVersionId`
- `accountId`
- `sourceType`
- `modelFamily`
- `algorithm`
- `failureHorizonDays`
- `minimumPositiveLabels`
- `minimumLabelCoverage`
- `cancellationRequested`: cooperative cancellation flag checked before major
  ML-service phases and before final artifact promotion.
- `trainingTimeoutSeconds`: optional ML-service timeout budget.
- `rows`

The response is a `TrainingRunResponse` with `runId`, `status`,
`datasetVersionId`, `trainingRunId`, `idempotencyKey`, `artifactBundleId`,
optional model artifact metadata, training metrics, feature snapshot metadata,
logs summary, and sanitized failure reason. Conflicting reuse of an
`idempotencyKey` with different dataset, feature snapshot, model family,
algorithm, horizon, hyperparameters, artifact target, or rows returns `409`
without overwriting existing artifact metadata. Canceled or timed-out training
returns a terminal non-success status and does not promote a final manifest URI.
An oversized request returns `413` before feature parsing, model fitting,
artifact write, or model registration work runs.

## GET /runs/{runId}

Returns the in-memory training run response for compatibility and local
inspection. This endpoint is internal and requires the ML-service boundary
headers when `ML_INTERNAL_AUTH_REQUIRED=true`. Production operator-visible
training state remains owned by analytics-service; this endpoint is not a
durable production run registry.

## Production ML metadata contracts

The production ML module treats training data, features, training runs, model
versions, and deployment policies as versioned metadata. Initial storage lives
in analytics-service; ML-service can later consume these contracts through
service APIs without scraping databases directly.

Canonical metric rows include `accountId`, `datasetVersionId`, `sourceType`,
`deviceId` or `externalDeviceKey` or `virtualDeviceKey`, `deviceType`,
optional `sourceId` and `sensorId`, `timestamp`, `metricName`,
`canonicalMetricName`, numeric `value`, `rawUnit`, `canonicalUnit`,
`qualityFlags`, `metadataJson`, `sourceLineage`, and creation metadata.

Dataset lifecycle:

- `DRAFT`
- `VALIDATING`
- `READY`
- `FAILED`
- `ARCHIVED`
- `DELETED`

Dataset source types:

- `INTERNAL_TELEMETRY`
- `EXTERNAL_IMPORT`
- `MIXED`

`READY` means the dataset version is usable by feature generation and training.
A `READY` version must have accepted canonical rows and must expose either an
artifact URI/hash or an immutable row reference plus a data hash once
materialization is complete. `FAILED` versions keep sanitized failure reasons
and validation reports, but must not be selected for feature generation or
training.

Dataset version summaries and details expose these immutable-data fields:

- `artifactUri`: object-store or service URI for a frozen dataset artifact.
- `artifactHash`: hash of the artifact content when an artifact exists.
- `immutableRowReference`: stable table/reference for frozen canonical rows.
- `dataHash`: deterministic hash of canonical row content.

Validation reports include `qualityProfile`, an object with row count, device
count, metric count, time range, coverage by device/metric, maximum sampling
gap, duplicate timestamp rate, non-finite or impossible value count, unit
normalization warnings, maintenance overlap count, and label coverage when
labels exist.

Label values:

- `NORMAL`
- `ANOMALY`
- `DEGRADATION`
- `MAINTENANCE`
- `CONFIRMED_FAILURE`
- `CORRECTIVE_MAINTENANCE`
- `PREVENTIVE_MAINTENANCE`
- `INSPECTION_NORMAL`
- `FALSE_POSITIVE`
- `SENSOR_FAULT`
- `UNRESOLVED_ANOMALY`
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
- `FAILURE_PREDICTION`
- `FORECASTING`

Training run statuses:

- `QUEUED`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `CANCELED`
- `ARCHIVED`
- `DELETED`

Model registry statuses:

- `CANDIDATE`
- `APPROVED`
- `ACTIVE`
- `REJECTED`
- `ARCHIVED`
- `DELETED`

Deployment scopes resolve in `DEVICE`, `DEVICE_TYPE`, `ACCOUNT`, `SYSTEM`,
then baseline fallback order. Deployment modes are `SHADOW`, `CANARY`,
`ACTIVE`, and `DISABLED`.

## GET /analytics/ml/datasets

Lists the latest dataset version for each account-scoped ML dataset. Each item
includes status, split strategy, source window, failure reason, and immutable
data metadata.

Optional query:

- `statusFilter=default|active|failed|archived|deleted|all`. `default`,
  `active`, and `current` hide archived/deleted versions and return the latest
  visible version per dataset. `failed`, `archived`, `deleted`, and `all` return
  matching dataset versions for cleanup review.

Example item:

```json
{
  "datasetId": "3e509cd3-dc0a-4904-af62-bfb877d9e4b7",
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "name": "Washer July active power",
  "description": "Internal telemetry for anomaly training",
  "sourceType": "INTERNAL_TELEMETRY",
  "version": 1,
  "status": "READY",
  "splitStrategy": "TIME_BASED",
  "featureConfigVersion": "feature-config-v1",
  "sourceWindowStart": "2026-07-01T00:00:00Z",
  "sourceWindowEnd": "2026-07-31T23:59:59Z",
  "createdAt": "2026-07-30T00:00:00Z",
  "readyAt": "2026-07-30T00:00:00Z",
  "failureReason": null,
  "artifactUri": "s3://voltcanary-ml/datasets/2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf/rows.jsonl",
  "artifactHash": "sha256:...",
  "immutableRowReference": "ml_canonical_metric_rows:dataset_version_id=2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "dataHash": "sha256:..."
}
```

## GET /analytics/ml/datasets/versions/{datasetVersionId}

Returns dataset summary metadata, validation reports, and lineage. Validation
reports include `qualityProfile`.

Example validation report:

```json
{
  "id": "e93d690f-f2f0-4e48-9d93-9ff45c68dcf1",
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "importedFileId": null,
  "acceptedRows": 744,
  "rejectedRows": 0,
  "warningCount": 1,
  "rejectedReasons": {},
  "mappingStats": {
    "accepted:activePowerW": 744
  },
  "qualityProfile": {
    "rowCount": 744,
    "deviceCount": 1,
    "metricCount": 1,
    "timeRange": {
      "from": "2026-07-01T00:00:00Z",
      "to": "2026-07-31T23:59:59Z"
    },
    "coverageByDeviceMetric": {
      "a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16:activePowerW": 744
    },
    "maxSamplingGapSeconds": 3600,
    "duplicateTimestampRate": 0.0,
    "nonFiniteImpossibleValueCount": 0,
    "unitNormalizationWarnings": 0,
    "maintenanceOverlapCount": 1,
    "labelCoverage": {}
  },
  "summary": "Telemetry selection is ready for training.",
  "createdAt": "2026-07-30T00:00:00Z"
}
```

## POST /analytics/ml/datasets/internal-telemetry/preview

Previews an internal telemetry dataset selection without mutating ML lifecycle
state. The endpoint authorizes the selected devices, discovers real
telemetry-service metrics for those devices, evaluates dataset quality gates,
returns candidate windows, and previews failure-prediction label readiness when
requested. It does not create dataset versions, canonical rows, labels, feature
snapshots, training runs, model versions, or deployment policies.

Request:

```json
{
  "modelFamily": "FAILURE_PREDICTION",
  "deviceIds": ["a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16"],
  "deviceType": "washer",
  "deviceMetadata": {
    "firmware": "2.4.1"
  },
  "metricSelectionMode": "COMMON",
  "selectedMetrics": ["activePowerW"],
  "from": "2026-07-01T00:00:00Z",
  "to": "2026-07-31T23:59:59Z",
  "horizonDays": 30,
  "splitStrategy": "TIME_BASED",
  "splitConfigJson": "{\"train\":0.7,\"validation\":0.15,\"test\":0.15}",
  "featureConfigVersion": "feature-config-v1",
  "minSamplesPerMetric": 24,
  "maxSamplingGapSeconds": 21600,
  "candidateLimit": 4
}
```

`metricSelectionMode` values are `COMMON` and `ALL_AVAILABLE`. `COMMON`
returns only metrics present on every selected device. `ALL_AVAILABLE` returns
the metric union with coverage ratios and partial-coverage warnings. No default
fallback or phantom metrics are selectable.

Response:

```json
{
  "previewId": "prev_4c59c3e0d62d8f12",
  "previewHash": "sha256:4c59c3e0d62d8f12...",
  "expiresAt": "2026-07-30T00:15:00Z",
  "status": "READY_WITH_WARNINGS",
  "recommendedCandidateId": "candidate-user-selected",
  "selectedWindow": {
    "from": "2026-07-01T00:00:00Z",
    "to": "2026-07-31T23:59:59Z",
    "source": "USER_SELECTED"
  },
  "availableMetrics": [
    {
      "metricName": "activePowerW",
      "displayName": "Active power",
      "canonicalMetricName": "active_power",
      "unit": "W",
      "devicesWithMetric": 1,
      "selectedDeviceCount": 1,
      "coverageRatio": 1.0,
      "sampleCount": 744,
      "maxSamplingGapSeconds": 3600,
      "duplicateTimestampRate": 0.0,
      "nonFiniteImpossibleValueCount": 0,
      "unitNormalizationWarnings": 0,
      "status": "READY",
      "blockingReasons": [],
      "warningReasons": []
    }
  ],
  "candidateWindows": [
    {
      "id": "candidate-user-selected",
      "kind": "USER_SELECTED",
      "from": "2026-07-01T00:00:00Z",
      "to": "2026-07-31T23:59:59Z",
      "score": 0.93,
      "status": "READY_WITH_WARNINGS",
      "recommended": true,
      "sampleCount": 744,
      "deviceCount": 1,
      "metricCount": 1,
      "coverageRatio": 1.0,
      "maxSamplingGapSeconds": 3600,
      "positiveLabels": 14,
      "normalLabels": 610,
      "unobservableLabels": 12,
      "maintenanceExcludedLabels": 8,
      "blockingReasons": [],
      "warningReasons": ["Window overlaps excluded maintenance periods."],
      "explanation": "User selected window."
    }
  ],
  "timeline": [
    {
      "bucketStart": "2026-07-01T00:00:00Z",
      "bucketEnd": "2026-07-03T13:59:59Z",
      "sampleCount": 60,
      "coverageRatio": 1.0,
      "status": "READY",
      "events": []
    }
  ],
  "labelReadiness": {
    "horizonDays": 30,
    "positiveLabels": 14,
    "normalLabels": 610,
    "unresolvedLabels": 3,
    "unobservableRows": 12,
    "maintenanceExcludedLabels": 8,
    "coverageRatio": 0.97,
    "status": "READY"
  },
  "qualityGates": [
    {
      "key": "minSamplesPerMetric",
      "label": "Minimum samples per metric",
      "status": "PASS",
      "actual": 744,
      "threshold": 24,
      "blocking": true,
      "message": "Selected metrics have enough samples."
    }
  ],
  "blockingReasons": [],
  "warningReasons": ["Window overlaps excluded maintenance periods."],
  "createRequestDefaults": {
    "metrics": ["activePowerW"],
    "from": "2026-07-01T00:00:00Z",
    "to": "2026-07-31T23:59:59Z",
    "splitStrategy": "TIME_BASED",
    "featureConfigVersion": "feature-config-v1",
    "minSamplesPerMetric": 24,
    "maxSamplingGapSeconds": 21600,
    "modelFamily": "FAILURE_PREDICTION",
    "horizonDays": 30,
    "metricSelectionMode": "COMMON"
  }
}
```

Preview response status values are `READY`, `READY_WITH_WARNINGS`, and
`BLOCKED`. Metric and timeline rows may also use `NO_DATA`. Quality gate status
values are `PASS`, `WARN`, and `BLOCK`.

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
  "maxSamplingGapSeconds": 21600,
  "previewId": "prev_4c59c3e0d62d8f12",
  "previewHash": "sha256:4c59c3e0d62d8f12...",
  "modelFamily": "FAILURE_PREDICTION",
  "horizonDays": 30,
  "metricSelectionMode": "COMMON"
}
```

`previewId`, `previewHash`, `modelFamily`, `horizonDays`, and
`metricSelectionMode` are optional and additive. Existing callers may omit them.
Workbench callers should send them when creating from a fresh preview so the
backend can detect drift between preview and immutable materialization.

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
When `previewHash` is supplied, analytics-service revalidates the request and
compares the current stateless preview fingerprint. A mismatch returns HTTP
`409`:

```json
{
  "code": "PREVIEW_STALE",
  "message": "Dataset preview is stale; request a fresh preview before creating.",
  "dependencies": [
    {
      "type": "preview",
      "id": "prev_4c59c3e0d62d8f12",
      "details": {
        "code": "PREVIEW_STALE",
        "submittedPreviewHash": "sha256:old",
        "currentPreviewHash": "sha256:new",
        "validationMessages": ["Selected metric activePowerW has partial device coverage."]
      }
    }
  ]
}
```
Fresh workbench creates store the selected preview summary under
`qualityProfile.selectedPreview`.

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
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
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
metric names, invalid or non-finite metric values, invalid label values, and
`existingDeviceId` values that do not belong to the importing account. Known
metrics also enforce compatible units, for example `activePowerW` must use `W`,
`voltage` must use `V`, `current` must use `A`, and `energyGrowth` must use
`Wh` when a unit is supplied.
Mappings reject credential-like field names such as password, secret, token,
credential, and api_key. Accepted rows are stored as imported training rows
linked to the imported file and validation report. CSV import creates an
account-scoped `EXTERNAL_IMPORT` dataset version and returns its
`datasetVersionId`. Imports with accepted rows remain `VALIDATING` until
canonical rows are materialized; imports with no accepted rows are `FAILED`.
The service must not mark an external dataset version `READY` until accepted
canonical rows exist.

If `existingDeviceIdColumn` is present and a row value is a UUID, the imported
row is linked to that existing device id. Every accepted row also receives a
stable `virtualDeviceKey` derived from the account id and external device key,
so imported-only devices remain usable for training. Device mappings are stored
per imported file and external device key.

## ML lifecycle action contract

Lifecycle mutation endpoints require ML write authorization and a JSON body with
a non-empty `reason`:

```json
{
  "reason": "Cleanup failed experiment",
  "force": false
}
```

`force` is reserved for future explicit-confirmation flows. The current backend
does not use it to bypass lineage protection. Default list endpoints hide
archived and deleted records; detail and audit records remain readable where
lineage requires traceability.

Before showing a destructive confirmation, clients can request an impact
preview. Impact endpoints do not mutate state and use the same account ownership
and reference checks as the matching lifecycle action:

```json
{
  "action": "delete",
  "blocked": true,
  "message": "Dataset version is referenced and should be archived instead of deleted",
  "dependencies": [
    {
      "type": "datasetVersion",
      "id": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
      "details": {
        "labels": 2,
        "featureSnapshots": 1,
        "trainingRuns": 0,
        "modelVersions": 0
      }
    }
  ]
}
```

Unsafe operations return HTTP `409` with dependency details:

```json
{
  "code": "MODEL_POLICY_REFERENCED",
  "message": "Model is active or referenced by deployment policies",
  "dependencies": [
    {
      "type": "deploymentPolicy",
      "id": "6db9a07f-9f03-4ba2-9c54-92bf641ef740",
      "details": {
        "scope": "ACCOUNT",
        "mode": "ACTIVE"
      }
    }
  ]
}
```

## POST /analytics/ml/datasets/versions/{datasetVersionId}/archive

Marks a dataset version `ARCHIVED` and preserves validation reports, hashes,
immutable row references, and lineage.

## GET /analytics/ml/datasets/versions/{datasetVersionId}/lifecycle-impact

Returns the impact preview for `action=archive` or `action=delete`.

## POST /analytics/ml/datasets/versions/{datasetVersionId}/delete

Marks only unreferenced `DRAFT` or `FAILED` dataset versions `DELETED`. Labels,
feature snapshots, training runs, or model versions referencing the dataset
produce `409 DATASET_REFERENCED`.

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
accepted when `allowPartialAcceptance` is true. JSONL import also creates an
account-scoped `EXTERNAL_IMPORT` dataset version and returns its
`datasetVersionId`.

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
  "modelFamily": "ANOMALY_DETECTION",
  "horizonDays": null,
  "featureTemplateId": "generic-appliance",
  "featureTemplateVersion": "template-v1",
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
  "featureDataHash": "sha256:...",
  "featureConfigHash": "sha256:...",
  "rowCount": 1200,
  "rejectedRowCount": 0,
  "artifactUri": "db://ml_feature_snapshots/296cb424-2b9f-43e6-b6da-fcd175d7f469",
  "immutableRowReference": "ml_feature_rows:feature_snapshot_id=296cb424-2b9f-43e6-b6da-fcd175d7f469",
  "modelFamily": "ANOMALY_DETECTION",
  "horizonDays": null,
  "featureTemplateId": "generic-appliance",
  "featureTemplateVersion": "template-v1",
  "createdAt": "2026-07-30T00:00:00Z"
}
```

The pipeline computes rolling mean/min/max/stddev, baseline deviation ratio,
missing-gap indicators, hour/day time features, device context, labels for
accepted external rows, and template-specific feature columns. Internal
telemetry dataset versions materialize immutable feature rows from canonical
metric rows. Repeating generation with the same dataset and feature config
produces the same feature schema hash and feature data hash.

## GET /analytics/ml/datasets/feature-snapshots

Returns account-owned active feature snapshots newest first. Archived and
deleted snapshots are excluded from default results so training selectors only
show usable snapshots.

Optional query parameters:

- `datasetVersionId` filters results to one dataset version.

Each item has the same response shape as `POST /analytics/ml/datasets/feature-snapshots`
and includes `datasetVersionId`, `modelFamily`, `horizonDays`,
`featureTemplateId`, `featureTemplateVersion`, row counts, hashes,
`lifecycleStatus`, and `createdAt` for training compatibility checks.

## POST /analytics/ml/datasets/feature-snapshots/{featureSnapshotId}/archive

Marks a feature snapshot `ARCHIVED` while keeping feature hashes, artifact URI,
row counts, and immutable row references.

## GET /analytics/ml/datasets/feature-snapshots/{featureSnapshotId}/lifecycle-impact

Returns the impact preview for `action=archive` or `action=delete`.

## POST /analytics/ml/datasets/feature-snapshots/{featureSnapshotId}/delete

Marks only unreferenced snapshots `DELETED`. Training run or model references
produce `409 FEATURE_SNAPSHOT_REFERENCED`.

## POST /analytics/ml/datasets/horizon-labels

Starts deterministic failure-horizon label generation for a selected READY
dataset version. The endpoint returns `202 Accepted` with a durable generation
job instead of waiting for labels to finish inside the gateway request. Existing
generated labels for the same dataset version and horizon are replaced when the
job completes. Manual labels are preserved.

Request:

```json
{
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "horizonDays": 30,
  "generationVersion": "failure-outcome-v1",
  "includePreventiveMaintenance": false
}
```

Rows before `CONFIRMED_FAILURE` or `CORRECTIVE_MAINTENANCE` outcomes within
the horizon are positive labels. Preventive maintenance is excluded from
positive labels unless explicitly included. Rows inside ML maintenance
exclusions are flagged as `MAINTENANCE`. Rows with no positive outcome become
`NORMAL` only when the future horizon is observable; recent rows are left
unlabeled.

Response:

```json
{
  "jobId": "6ec3f36d-d1f9-4b21-a442-b7ee0d0385c1",
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "status": "QUEUED",
  "submittedAt": "2026-07-10T00:00:00Z",
  "startedAt": null,
  "completedAt": null,
  "progress": 0,
  "message": "Queued horizon label generation",
  "failureReason": null,
  "horizonDays": 30,
  "generationVersion": "failure-outcome-v1",
  "includePreventiveMaintenance": false,
  "inputLineageHash": "sha256:...",
  "result": null
}
```

Repeated requests with the same account, dataset version, horizon,
generation version, preventive-maintenance flag, and input lineage hash return
the existing `QUEUED`, `RUNNING`, or `COMPLETED` job. Failed jobs are not reused,
so operators can retry after fixing the failure condition.

## GET /analytics/ml/datasets/horizon-labels/{jobId}

Returns the current horizon label generation job status for the caller account.
`status` is one of `QUEUED`, `RUNNING`, `COMPLETED`, or `FAILED`. `failureReason`
is present only for `FAILED` jobs and is sanitized for operator display.

Completed response:

```json
{
  "jobId": "6ec3f36d-d1f9-4b21-a442-b7ee0d0385c1",
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "status": "COMPLETED",
  "submittedAt": "2026-07-10T00:00:00Z",
  "startedAt": "2026-07-10T00:00:02Z",
  "completedAt": "2026-07-10T00:00:32Z",
  "progress": 100,
  "message": "Horizon label generation completed",
  "failureReason": null,
  "horizonDays": 30,
  "generationVersion": "failure-outcome-v1",
  "includePreventiveMaintenance": false,
  "inputLineageHash": "sha256:...",
  "result": {
    "lineageHash": "sha256:...",
    "generatedLabels": 1200,
    "positiveLabels": 18,
    "normalLabels": 1100,
    "maintenanceExcludedLabels": 42,
    "unresolvedLabels": 8,
    "unobservableRows": 32
  }
}
```

Generated label responses include `horizonDays`, `generationVersion`,
`sourceOutcomeIdsJson`, and `lineageHash` for auditability.

Feature vector contracts are versioned separately from the implementation
version. `feature-vector-contract-v1` defines common row identity columns
(`deviceKey`, `metricName`, `timestamp`) plus canonical generated, required,
optional, and label columns for each model family. `ANOMALY_DETECTION`
contracts require anomaly-oriented rolling, baseline deviation, missing-data,
and time features when those families are enabled. `DEGRADATION_SCORING`
contracts add long-window trend, drift, runtime, and maintenance proximity
columns when enabled. `FAILURE_PREDICTION` contracts require a positive
`horizonDays` value and use horizon labels such as `failureWithinHorizon` and
`failureOutcomeType`. The `featureSchemaHash` includes the ordered contract
metadata and the `featureConfigHash`, so changing a contract version, template,
model family, horizon, enabled family list, or required column set creates a new
schema hash even when the persisted row shape is otherwise compatible.

Supported template ids are:

- `generic-appliance` / `template-v1`: adds `applianceLoadRatio` and
  `applianceActivityFlag` for broad appliance models.
- `fridge-freezer` / `template-v1`: adds `fridgeTemperatureBandC`,
  `compressorDutyProxy`, and `defrostCycleRiskFlag` for fridge, freezer, and
  refrigerator-like devices.

Live inference requests include an online `featureVector`, `featureContract`,
`featureGenerationStatus`, and `featureGenerationErrors`. When a selected model
requires a schema that cannot be produced online, the ML-service returns
`FEATURE_SCHEMA_MISMATCH`. When required live features are missing and baseline
fallback is disabled, it returns `FEATURES_UNAVAILABLE` with explicit missing
feature reasons. Failure models additionally return `INSUFFICIENT_LABELS` when
the selected artifact's label approval gates fail.

## POST /analytics/ml/training-runs

Creates an asynchronous training run. The production anomaly-detection path
delegates fitting to ML-service, which trains a tabular sklearn IsolationForest
pipeline from the materialized feature snapshot and returns a durable model
bundle manifest.

`FAILURE_PREDICTION` training requests add horizon and label-gate parameters:
`failureHorizonDays`, `minimumPositiveLabels`, and `minimumLabelCoverage`.
These fields are stored with the run hyperparameters and passed to ML-service
for supervised target construction and approval gates.

Failure-prediction training is rejected before fitting when usable labels do
not include the configured minimum positive failure outcomes, at least one
negative non-failure example, or the configured minimum label coverage. Failed
responses include target-construction metrics such as `failurePositiveLabels`,
`failureNegativeLabels`, `failureExcludedSamples`, `failurePositiveRate`, and
`failureClassImbalanceRatio`.

Supported initial `FAILURE_PREDICTION` algorithms:

- `sklearn-logistic-regression-failure-v1`
- `sklearn-random-forest-failure-v1`

Request:

```json
{
  "datasetVersionId": "2aa8e81d-f3f5-48af-9f2d-6f7fef48b7cf",
  "featureSnapshotId": "296cb424-2b9f-43e6-b6da-fcd175d7f469",
  "modelFamily": "FAILURE_PREDICTION",
  "algorithm": "sklearn-logistic-regression-failure-v1",
  "failureHorizonDays": 14,
  "minimumPositiveLabels": 20,
  "minimumLabelCoverage": 0.8,
  "hyperparametersJson": "{}"
}
```

Response status is initially `QUEUED`. A worker moves runs through `RUNNING`
to `SUCCEEDED` or `FAILED`. Analytics-service owns these status transitions;
ML-service owns fitting, model serialization, and artifact manifest creation.
Successful runs store evaluation metrics, artifact URI/hash/manifest metadata,
log summary, timestamps, and register a `CANDIDATE` model version only after a
model-only smoke inference loads and scores the returned `modelVersionId`.
Failure reasons are sanitized and stored without stack traces.

Failure-prediction model bundles use
`artifactContractVersion=failure-prediction-artifact-v1`. The manifest records
the model family, algorithm, feature schema hash, runtime dependency versions,
input contract, file list, file hashes, and bundle hash. Required bundle files
are:

- `model.json`: model metadata, classifier scoring type, feature columns,
  horizon, label counts, metrics, and target contract.
- `model.joblib`: serialized sklearn pipeline containing the dict vectorizer,
  optional scaler, and fitted classifier.
- `model-card.json`: model card with intended use, target, training data,
  evaluation summary, baseline comparison, runtime details, and limitations.
- `preprocessor.json`: runtime loader metadata, dataset version, feature schema
  hash, ordered feature columns, horizon, label counts, and the same target
  contract as `model.json`.

Evaluation metadata uses a time-based holdout. Numeric metrics include
precision, recall, F1, PR-AUC, false alerts per device-month, average lead time,
and comparison against `training-positive-rate-baseline-v1` with baseline
precision/recall/F1/PR-AUC plus candidate lift fields.

Approval gate metadata is stored in `approvalGates` on the manifest,
`model.json`, and `model-card.json`. `failure-approval-gates-v1` reports
`promotionEligible`, overall status, and per-gate observed values, thresholds,
pass/fail state, and reasons for minimum positives, label coverage, time-based
evaluation availability, precision, recall, false-alert rate, artifact load
test, and feature schema compatibility.

Prediction responses preserve existing anomaly and degradation fields and may
include failure-specific fields when a failure model scores the device:
`modelVersionId`, `modelFamily`, `failureRiskScore`, `failureHorizonDays`,
`failureRiskBand`, `riskDrivers`, `labelCoverage`, and `trainingDataWindow`.
Risk drivers are structured objects suitable for traceability and UI display.
When analytics-service supplies only `modelVersionId`, ML-service may resolve
the model from its registry metadata; production requests should still include
manifest and artifact hash metadata for durable object-store loading. Failure
models report `anomalyScore=0.0` and `degradationScore=0.0` because those
outputs remain separate model families.

Training run responses include worker-control metadata: `leaseOwner`,
`leaseExpiresAt`, `heartbeatAt`, `cancellationRequested`, `retryCount`, and
`staleRecoveredAt`. Running cancellations are cooperative: the API sets
`cancellationRequested`, and the worker transitions to `CANCELED` at the next
checkpoint before candidate registration.

## GET /analytics/ml/training-runs

Lists recent training runs for the current account.

Optional query:

- `statusFilter=default|active|failed|archived|deleted|all`. Default hides
  archived/deleted runs. `failed` includes `FAILED` and `CANCELED` runs for
  retry or cleanup workflows.

## POST /analytics/ml/training-runs/{runId}/cancel

Cancels a `QUEUED` or `RUNNING` training run and stores completion metadata.

## POST /analytics/ml/training-runs/{runId}/retry

Creates a new `QUEUED` training run from a `FAILED` or `CANCELED` run using the
same dataset version, feature snapshot, model family, algorithm, and
hyperparameters.

## POST /analytics/ml/training-runs/{runId}/archive

Archives a completed, failed, or canceled training run. Queued or running runs
must be canceled before archival. Model lineage and failure metadata remain
readable.

## GET /analytics/ml/training-runs/{runId}/lifecycle-impact

Returns the impact preview for `action=archive` or `action=delete`.

## POST /analytics/ml/training-runs/{runId}/delete

Marks an unreferenced `FAILED` or `CANCELED` training run `DELETED`. Successful
runs or runs referenced by model versions return `409
TRAINING_RUN_MODEL_REFERENCED` and should be archived instead.

## GET /analytics/ml/models

Lists account model versions with model key, version, family, algorithm,
status, dataset version, feature snapshot, artifact hash/URI, compatibility,
evaluation metrics, and registry origin fields. `modelOrigin` is `TRAINED`,
`DEMO`, or `SYSTEM_HEURISTIC`; `demoModel` and `systemHeuristic` are boolean
shortcuts for frontend labeling and deployment filtering. Production demo
model seeding is disabled by default and must be explicitly enabled for local
or demo environments.

Optional query:

- `statusFilter=default|active|failed|archived|deleted|all`. Default hides
  archived/deleted model versions. `failed` maps to rejected model versions.

## GET /analytics/ml/models/{modelVersionId}

Returns one model version detail.

## POST /analytics/ml/models/{modelVersionId}/approve

Moves a `CANDIDATE` or `REJECTED` model to `APPROVED`, records approval
metadata, and writes an audit event.

## POST /analytics/ml/models/{modelVersionId}/reject

Moves a non-active model to `REJECTED` and writes an audit event.

## POST /analytics/ml/models/{modelVersionId}/archive

Moves a non-active model to `ARCHIVED` and writes an audit event.

## GET /analytics/ml/models/{modelVersionId}/lifecycle-impact

Returns the impact preview for `action=delete`, including deployment policy and
historical prediction references.

## POST /analytics/ml/models/{modelVersionId}/delete

Marks an unreferenced `CANDIDATE` or `REJECTED` model version `DELETED` and
writes an audit event. Active models, models referenced by non-disabled
deployment policies, and models with historical prediction records are blocked
with `409 MODEL_POLICY_REFERENCED` or `409 MODEL_PREDICTION_REFERENCED`.

## GET /analytics/ml/deployment-policies

Lists deployment policies for the current account.

Optional query:

- `statusFilter=default|active|failed|archived|deleted|all`. Default hides
  soft-deleted policies. `active` shows non-disabled policies, `failed` and
  `archived` show disabled policies, and `deleted` shows soft-deleted policies.

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

## POST /analytics/ml/deployment-policies/{policyId}/disable

Disables the selected policy, enables fallback, and writes an audit event.
Runtime policy resolution no longer selects disabled policies.

## GET /analytics/ml/deployment-policies/{policyId}/lifecycle-impact

Returns the impact preview for `action=disable` or `action=delete`, including
scope, mode, selected model, fallback state, and soft-delete state.

## POST /analytics/ml/deployment-policies/{policyId}/delete

Soft-deletes a disabled deployment policy so it is removed from default policy
lists while historical prediction traceability and deployment audit references
remain readable. Non-disabled policies return `409 POLICY_ACTIVE`.

## GET /analytics/ml/labels/{labelId}/lifecycle-impact

Returns the impact preview for `action=delete`. Generated labels return
`blocked=true` with source and lineage hash details.

## POST /analytics/ml/labels/{labelId}/delete

Soft-deletes a manual label with audit metadata. Deleted labels are excluded
from future training label sets. Generated labels are blocked with `409
GENERATED_LABEL_LINEAGE`; regenerate or supersede generated labels for the same
dataset, horizon, and generation config instead of hard-deleting lineage.

## GET /analytics/ml/deployment-policies/resolve

Resolves the effective model policy in order: `DEVICE`, `DEVICE_TYPE`,
`ACCOUNT`, `SYSTEM`, then baseline heuristic fallback. Live recalculation
passes device metadata from device-service, so a device-type policy is selected
when no device policy exists and the device metadata includes `type`.

The response includes the selected `policySource`, `policyId`,
`modelVersionId`, `mode`, `resolvedDeviceType`, `canaryPercent`,
`canaryIncluded` when evaluated by runtime prediction, `fallbackEnabled`, and
`fallbackReason`.

## Prediction traceability

Analytics-service persists prediction traceability on every recalculation:
model version id, policy source, policy id, deployment mode, resolved device
type, canary percent, canary inclusion, prediction path, input hash, feature
schema hash when available, fallback reason, confidence, signals, explanations,
and recommended actions. Canary inclusion is deterministic using account id,
device id, model version id, and configured percent; devices outside the cohort
persist a fallback trace with `canaryIncluded=false`.

`SHADOW` deployments create an additional shadow prediction record with
`shadowPrediction=true`; latest prediction reads exclude shadow records so
shadow scoring does not change active risk or user-facing recommendations.
Fallback traces use `predictionPath=FALLBACK` and preserve the selected policy
metadata for audit.

## Artifact cleanup boundary

Artifact cleanup is dry-run first. A cleanup preview must compare artifacts in
the configured store with references from training runs, model versions,
deployment policies, prediction traceability, and audit records. Referenced
artifacts are returned as blocked and must not be deleted by automated cleanup.
Only unreferenced artifacts are cleanup candidates.

## Frontend status boundary

The frontend does not call ML-service directly. It reads the latest persisted prediction through analytics-service. Model name, version, type, readiness status, prediction status, confidence, signals, explanations, recommended actions, input hash, model id, policy source, resolved device type, canary fields, fallback reason, prediction path, and shadow marker are read-only traceability fields. Missing or unavailable ML metadata is represented with controlled status values; internal endpoints and errors are not exposed.
