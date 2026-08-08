# ML Dataset Workbench

Status: target architecture, not implemented.

Date: 2026-08-08

This document defines the production-ready target for the VoltCanary internal
telemetry dataset creation workflow. It replaces the current backend-shaped
"Create internal dataset" form with a data engineering workbench that discovers
real telemetry, recommends valid time windows, previews quality gates, and only
then creates immutable ML dataset versions.

The intended reader is a data engineer, ML engineer, frontend engineer, or
backend engineer implementing the next ML console iteration.

## Current Platform Context

VoltCanary already has the main lifecycle building blocks:

- Device registry and account ownership in `device-service`.
- Telemetry ingestion, normalized time-series storage, metric discovery,
  chart readings, and quality flags in `telemetry-service`.
- ML metadata, dataset versions, validation reports, canonical metric rows,
  labels, feature snapshots, training runs, model versions, deployment
  policies, lifecycle actions, and audit behavior in `analytics-service`.
- Training and inference runtime in `ml-service`.
- A frontend ML Settings page in `frontend/src/pages/MlSettingsPage.tsx`.
- Docker verification through `scripts/ml-failure-prediction-docker-smoke.ps1`.

The current internal telemetry dataset UI is still too close to a raw backend
form:

- Device selection uses a native multi-select that is hard to scan and hard to
  use for cohorts.
- Metric selection is a plain checkbox grid.
- Metric availability falls back to default ML metrics when selected device
  metadata has no metric list.
- The operator must enter `from` and `to` manually without seeing telemetry
  density, gaps, or label distribution.
- Quality feedback appears after dataset creation, not before the operator
  submits the request.
- Failure-prediction readiness is split across dataset creation, horizon label
  generation, feature snapshot generation, and training forms.

The existing backend creation endpoint is valuable and must remain supported:

```text
POST /analytics/ml/datasets/internal-telemetry
```

The production-ready mechanism adds a preview and recommendation layer before
that endpoint, rather than moving validation into the browser.

## Goals

The workbench SHALL let a data engineer answer these questions before creating
an immutable dataset version:

- Which devices or cohort are being trained?
- Which telemetry metrics actually exist for those devices?
- Which metrics are common across the selected devices?
- Which selected time windows are dense enough to train on?
- Where are telemetry gaps, bad values, duplicates, and unit normalization
  issues?
- For failure prediction, how many positive, normal, excluded, unresolved, and
  unobservable labels will exist for the selected horizon?
- Which proposed window is recommended and why?
- What exact dataset creation request will be submitted?
- Will the downstream feature snapshot and training path likely pass?

The workbench SHALL reduce trial-and-error dataset creation. Dataset creation
should be the final commit step after the system has already previewed the same
selection using backend-owned validation logic.

## Non-Goals

This mechanism does not replace:

- External CSV/JSONL import.
- Feature snapshot generation.
- Training run creation.
- Model approval and deployment policy lifecycle.
- Telemetry monitoring pages for day-to-day device operations.

The workbench may reuse chart and metric components from telemetry monitoring,
but its job is ML dataset selection and readiness, not general observability.

## Users

Primary user: data engineer.

Responsibilities:

- Build trainable datasets from internal telemetry.
- Understand data quality before creating dataset versions.
- Select metrics and time windows that match the ML target.
- Inspect failure label readiness for supervised prediction.
- Avoid accidental leakage, partial coverage, or impossible training gates.

Secondary user: ML engineer.

Responsibilities:

- Check whether a dataset is fit for feature generation and training.
- Compare dataset versions and feature snapshots.
- Diagnose why training failed or would fail.

Operator user:

- May inspect recommended windows and create a dataset for known device groups,
  but should not be required to understand raw ids, feature schema hashes, or
  validation JSON.

## Design Principles

### Algorithm First, Visual Override Second

The backend SHALL recommend valid candidate windows. The chart exists to explain
and adjust those candidates, not to force the user to manually find valid data.

Default workflow:

```text
select model family -> select device scope -> backend recommends windows
-> user inspects timeline -> user accepts or adjusts -> backend previews gates
-> user creates immutable dataset
```

### Backend Owns Truth

The frontend SHALL not independently decide that a dataset is valid. It may
render intermediate chart data, but the authoritative readiness decision lives
in `analytics-service`, using the same telemetry queries and quality rules as
dataset materialization.

### No Phantom Metrics

Metric selectors SHALL list only metrics that exist for selected devices in the
selected or previewed data scope. The UI SHALL not present default fallback
metrics as selectable metrics when the selected devices do not expose them.

### Preview Must Match Create

The preview response SHALL include a deterministic preview hash or token derived
from the selection, validation rules, and source high-water marks. Dataset
creation SHALL either reuse that preview contract or revalidate and explain any
change before committing.

### Immutable Data Products

Dataset versions remain immutable. A changed device set, metric set, time
window, split strategy, horizon, or gate configuration creates a new dataset
version.

### Training-Serving Parity

The workbench SHALL preserve the ML lineage required to compare offline feature
snapshots with online inference features. Point-in-time correctness and failure
horizon leakage checks are first-class concerns.

## Target User Experience

The `ML Settings -> Datasets` tab should become `Dataset Workbench`.

High-level layout:

```text
+--------------------------------------------------------------------------+
| Workflow: [Anomaly detection] [Failure prediction]  Horizon: [30 days]   |
+----------------------+--------------------------------+------------------+
| Scope                | Telemetry timeline             | Readiness        |
| - Device search      | - Primary metric chart         | - Overall status |
| - Type/status filter | - Candidate windows            | - Quality gates  |
| - Selected cohort    | - Maintenance/failure labels   | - Label coverage |
| - Selection summary  | - Brush selected range         | - Create summary |
+----------------------+--------------------------------+------------------+
| Metric matrix                                                             |
| metric | unit | devices covered | samples | max gap | status | selected  |
+--------------------------------------------------------------------------+
| Dataset details | split | feature config | gates | [Create dataset]      |
+--------------------------------------------------------------------------+
```

### Workflow Header

Fields:

- `Model family`: `ANOMALY_DETECTION` or `FAILURE_PREDICTION`.
- `Horizon days`: required and positive when model family is
  `FAILURE_PREDICTION`.
- `Objective`: optional display-only summary such as "Find stable anomaly
  baseline" or "Predict confirmed failure or corrective maintenance in N days".

Behavior:

- Switching model family recalculates recommendations and preview.
- Failure-prediction mode shows label readiness and event overlays.
- Anomaly-detection mode prioritizes stable non-maintenance windows.

### Scope Builder

Device selection SHALL be a searchable selector, not a native multi-select.

Required behaviors:

- Search by device name, id, type, status, location when available.
- Filter by device type and status.
- Support single-device and multi-device cohort selection.
- Show selected cohort summary: device count, active count, inactive count,
  telemetry-present count, and missing-telemetry count.
- Mark stale restored draft devices as unavailable and block create until they
  are removed or reselected.
- Submit canonical device ids to the backend.

For cohorts:

- `Common metrics` mode selects metrics present on every selected device.
- `All available with coverage` mode allows partial metrics but clearly shows
  coverage and downstream impact.
- A metric present on zero selected devices must not be selectable.

### Metric Discovery

Metric discovery SHOULD use telemetry-service metric metadata, not only device
metadata. Device metadata may seed the UI while preview loads, but telemetry
metadata is authoritative.

Existing related endpoint:

```text
GET /telemetry/devices/{deviceId}/metrics
```

Target behavior:

- For one selected device, show only metrics returned for that device.
- For multiple devices, aggregate metrics into coverage rows.
- Preserve canonical metric names in API requests.
- Display user-friendly labels and units.
- Show a clear empty state when no telemetry metrics exist.

Metric row fields:

- `metricName`
- `displayName`
- `canonicalMetricName`
- `unit`
- `devicesWithMetric`
- `selectedDeviceCount`
- `coverageRatio`
- `sampleCount`
- `maxSamplingGapSeconds`
- `duplicateTimestampRate`
- `nonFiniteImpossibleValueCount`
- `unitNormalizationWarnings`
- `status`: `READY`, `WARNING`, `BLOCKED`, or `NO_DATA`
- `blockingReasons`
- `warningReasons`

### Telemetry Timeline

The timeline SHALL visualize the selected scope and explain candidate windows.

Required layers:

- Primary metric line for the currently focused metric.
- Density or coverage band across the full queried range.
- Candidate window overlays.
- Selected window overlay.
- Maintenance exclusion overlays.
- Failure/corrective-maintenance event markers in failure-prediction mode.
- Gap markers for intervals exceeding `maxSamplingGapSeconds`.

Color semantics:

- Green: recommended window, gates pass.
- Yellow: usable with warnings.
- Red: blocked window.
- Blue: user-selected active window.
- Purple or another distinct accent: failure/maintenance event or positive label
  evidence.
- Gray: excluded or unavailable data interval.

The visual style should remain operational and dense. Avoid decorative chart
cards or oversized explanations.

### Time Selection

The system SHALL recommend candidate windows automatically. Manual chart
selection is an override.

Supported controls:

- Quick range: `24h`, `7d`, `30d`, `90d`, `custom`.
- Candidate list: `Best coverage`, `Recent stable`, `Failure-rich` when
  applicable.
- Brush selection on the timeline.
- Exact `From` and `To` datetime inputs.
- Reset to recommended window.

The chart brush and datetime fields SHALL stay synchronized.

When the user changes time range:

1. The frontend updates local draft state.
2. The frontend requests a new backend preview.
3. The readiness panel and metric matrix refresh from the preview response.
4. The create button remains disabled while preview is stale or loading.

### Candidate Windows

The backend SHALL return candidate windows ranked for the selected model family.

Minimum candidate types:

- `BEST_COVERAGE`: highest data coverage and lowest quality risk.
- `RECENT_STABLE`: recent enough for current behavior, with acceptable gaps.
- `FAILURE_RICH`: failure-prediction window with enough positive labels and
  normal examples.
- `USER_SELECTED`: returned when the user brushes or types a custom range.

Candidate fields:

- `id`
- `kind`
- `from`
- `to`
- `score`
- `status`
- `recommended`
- `sampleCount`
- `deviceCount`
- `metricCount`
- `coverageRatio`
- `maxSamplingGapSeconds`
- `positiveLabels`
- `normalLabels`
- `unobservableLabels`
- `maintenanceExcludedLabels`
- `blockingReasons`
- `warningReasons`
- `explanation`

### Readiness Panel

The readiness panel SHALL summarize whether the selected scope can be turned
into a dataset version and whether downstream ML steps are likely to pass.

Required groups:

- Scope: devices, metrics, time range, selected candidate.
- Data: accepted rows, rejected rows, sample density, max gap, duplicate rate,
  non-finite count, unit warnings.
- Failure labels: horizon, positive labels, normal labels, unresolved labels,
  unobservable rows, maintenance-excluded rows.
- Downstream: feature snapshot compatibility, expected feature families,
  training gate readiness.
- Final decision: `READY`, `READY_WITH_WARNINGS`, or `BLOCKED`.

Readiness SHALL use field-level blocking reasons. A blocked create button with
no explanation is not acceptable.

### Dataset Commit Area

The final commit area collects only fields that are not discoverable from
preview:

- dataset name;
- description;
- split strategy;
- feature config version;
- minimum samples per metric;
- maximum sampling gap seconds.

The create button SHALL show the committed selection summary before submission:

- selected devices;
- selected metrics;
- `from` and `to`;
- model family;
- horizon when applicable;
- preview status;
- preview hash or token.

After successful creation:

- clear the draft;
- show dataset version id and status;
- show validation report summary;
- allow the next action: generate labels, generate feature snapshot, or inspect
  dataset detail depending on model family and readiness.

## Backend Architecture

### Service Ownership

`analytics-service` owns ML dataset workbench preview because it already owns:

- account-scoped ML authorization;
- dataset version creation;
- validation reports;
- labels;
- maintenance outcome read models;
- canonical ML rows;
- feature snapshot and training compatibility rules.

`telemetry-service` owns telemetry storage and raw metric queries.

`device-service` owns device registry and account ownership.

`ml-service` is not in the preview request path. It trains and scores after
datasets and feature snapshots exist.

### New Preview Endpoint

Target endpoint:

```text
POST /analytics/ml/datasets/internal-telemetry/preview
```

Purpose:

- discover real selected-device metrics;
- summarize telemetry density and quality;
- recommend candidate windows;
- evaluate dataset creation gates;
- evaluate failure-prediction label readiness when horizon is present;
- return a preview hash or token that can be compared during create.

This endpoint is read-only and MUST NOT create dataset versions, canonical rows,
labels, feature snapshots, training runs, or model versions.

Request:

```json
{
  "modelFamily": "FAILURE_PREDICTION",
  "deviceIds": ["a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16"],
  "deviceType": "washer",
  "metricSelectionMode": "COMMON",
  "selectedMetrics": ["activePowerW", "voltageV"],
  "from": "2026-07-01T00:00:00Z",
  "to": "2026-07-31T23:59:59Z",
  "quickRange": null,
  "horizonDays": 30,
  "splitStrategy": "TIME_BASED",
  "featureConfigVersion": "feature-config-v1",
  "minSamplesPerMetric": 24,
  "maxSamplingGapSeconds": 21600,
  "candidateLimit": 3
}
```

Response:

```json
{
  "previewId": "prev_20260808_001",
  "previewHash": "sha256:...",
  "expiresAt": "2026-08-08T15:30:00Z",
  "status": "READY_WITH_WARNINGS",
  "recommendedCandidateId": "candidate-best-coverage",
  "selectedWindow": {
    "from": "2026-07-01T00:00:00Z",
    "to": "2026-07-31T23:59:59Z",
    "source": "USER_SELECTED"
  },
  "availableMetrics": [
    {
      "metricName": "activePowerW",
      "displayName": "Active Power",
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
      "id": "candidate-best-coverage",
      "kind": "BEST_COVERAGE",
      "from": "2026-07-01T00:00:00Z",
      "to": "2026-07-31T23:59:59Z",
      "score": 0.93,
      "status": "READY_WITH_WARNINGS",
      "recommended": true,
      "sampleCount": 744,
      "deviceCount": 1,
      "metricCount": 2,
      "coverageRatio": 0.98,
      "maxSamplingGapSeconds": 3600,
      "positiveLabels": 14,
      "normalLabels": 610,
      "unobservableLabels": 12,
      "maintenanceExcludedLabels": 8,
      "blockingReasons": [],
      "warningReasons": ["Window overlaps excluded maintenance periods."],
      "explanation": "Highest coverage with sufficient failure labels."
    }
  ],
  "timeline": [
    {
      "bucketStart": "2026-07-01T00:00:00Z",
      "bucketEnd": "2026-07-01T01:00:00Z",
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
    "metrics": ["activePowerW", "voltageV"],
    "from": "2026-07-01T00:00:00Z",
    "to": "2026-07-31T23:59:59Z",
    "splitStrategy": "TIME_BASED",
    "featureConfigVersion": "feature-config-v1",
    "minSamplesPerMetric": 24,
    "maxSamplingGapSeconds": 21600
  }
}
```

### Create Endpoint Compatibility

The existing create endpoint remains:

```text
POST /analytics/ml/datasets/internal-telemetry
```

The target architecture SHOULD add optional preview fields:

```json
{
  "previewId": "prev_20260808_001",
  "previewHash": "sha256:..."
}
```

Compatibility rules:

- Existing callers may continue to create datasets without preview fields.
- The frontend workbench SHOULD submit preview fields whenever available.
- If preview fields are supplied and the recomputed validation does not match,
  the backend SHOULD fail with `409 PREVIEW_STALE` or return a structured
  warning requiring the user to refresh preview.
- Create still reruns authoritative validation and persists the final validation
  report.
- Preview never guarantees success if telemetry changed, permissions changed,
  or maintenance exclusions changed between preview and create.

### Preview Hash

The preview hash SHOULD be deterministic and include:

- account id;
- sorted selected device ids;
- sorted selected metrics;
- model family;
- horizon days;
- `from` and `to`;
- split strategy;
- feature config version;
- quality gate thresholds;
- telemetry source high-water marks when available;
- maintenance outcome version or latest relevant update timestamp;
- preview algorithm version.

The hash MUST NOT include raw telemetry values directly. It is a contract
fingerprint, not a data export.

### Candidate Window Algorithm

The recommendation algorithm SHOULD operate over bucketed telemetry summaries,
not raw browser-side points.

Inputs:

- selected devices or cohort filters;
- selected or discoverable metrics;
- requested range or default search range;
- model family;
- horizon days for failure prediction;
- min sample threshold;
- max gap threshold;
- maintenance exclusions;
- generated or previewed failure labels.

Scoring dimensions:

- coverage ratio;
- sample density;
- maximum sampling gap;
- duplicate timestamp rate;
- non-finite or impossible values;
- unit normalization warnings;
- freshness;
- maintenance exclusion overlap;
- label coverage for failure prediction;
- positive/normal label balance for failure prediction;
- leakage risk near the right edge of a horizon window.

Example scoring shape:

```text
score = 0.30 * coverageScore
      + 0.20 * densityScore
      + 0.15 * gapScore
      + 0.15 * qualityScore
      + 0.10 * freshnessScore
      + 0.10 * labelScore
      - maintenancePenalty
      - leakagePenalty
```

The exact weights are implementation details, but the response MUST explain the
selected candidate in user-facing terms.

### Failure Prediction Label Readiness

For `FAILURE_PREDICTION`, preview SHALL evaluate the selected horizon before
dataset creation or at least before feature snapshot generation.

Rules:

- Positive labels are confirmed failure or corrective maintenance outcomes
  within the prediction horizon.
- Normal labels require an observable horizon with no positive outcome.
- Rows near the right edge of available telemetry may be unobservable because
  the horizon has not elapsed.
- Maintenance periods excluded from ML must be counted and displayed.
- Preventive maintenance is not positive by default unless explicitly included
  by the label-generation configuration.
- Unresolved anomalies and sensor faults must be visible, not silently counted
  as normal examples.

Preview SHOULD show what label generation would produce, but it MUST NOT create
or replace generated labels. Actual label generation remains a separate
lineage-changing action.

### Quality Gates

Gate severities:

- `PASS`: no action required.
- `WARN`: dataset may be created, but issue must be visible in the validation
  report and UI.
- `BLOCK`: create must be disabled or backend must reject the request.

Blocking conditions:

- no selected devices;
- selected device not accessible to the account;
- no telemetry metrics exist for selected scope;
- no selected metrics;
- selected metric exists on zero selected devices;
- `from >= to`;
- selected range exceeds telemetry query constraints;
- accepted row count is zero;
- every selected device-metric pair fails minimum samples when common mode is
  required;
- maximum gap exceeds a blocking threshold for all selected metrics;
- failure prediction selected with missing or non-positive horizon;
- failure prediction has no positive labels when supervised training gates
  require positives;
- failure prediction label coverage is below configured minimum and cannot be
  improved by candidate selection.

Warning conditions:

- metric coverage is partial in all-available mode;
- maintenance exclusions remove a meaningful share of rows;
- duplicate timestamp rate is non-zero but below the rejection threshold;
- unit normalization was required;
- data is stale compared with the selected training objective;
- label class balance is weak but above the hard minimum;
- candidate window is valid but not the top recommendation.

### Telemetry Query Strategy

The preview endpoint SHOULD avoid N devices times M metrics raw chart calls when
cohorts grow.

Near-term acceptable implementation:

- For small selections, analytics-service can call existing telemetry readings
  endpoints and aggregate summaries.
- The frontend can fetch one focused chart series for display after preview.

Production target:

- Add or use a telemetry-service summary endpoint that returns bucketed coverage
  by device, metric, and time range.
- Keep raw chart points capped for UI rendering.
- Use export/paginated paths only for dataset materialization, not preview.

Potential telemetry summary endpoint:

```text
POST /telemetry/query/coverage
```

or analytics-owned adapter:

```text
POST /analytics/ml/datasets/internal-telemetry/preview
```

The public frontend contract should remain analytics-owned. Telemetry-service
summaries may stay an internal service boundary.

### Data Persistence

Preview responses may be ephemeral. Dataset versions are durable.

Durable records after create:

- `ml_datasets`
- `ml_dataset_versions`
- `ml_dataset_validation_reports`
- `ml_dataset_lineage`
- `ml_canonical_metric_rows`

Preview persistence options:

- No database persistence: compute preview on demand and return hash.
- Short-lived cache: store preview by account and `previewId` with TTL.
- Audit-only persistence: store only create-time selected preview metadata.

Recommended first production implementation:

- Start with stateless preview plus deterministic hash.
- Store selected preview summary inside the final validation report
  `qualityProfile`.
- Add short-lived cache only if preview latency becomes a problem.

### Authorization And Isolation

Preview SHALL enforce the same account and device access rules as dataset
creation.

Requirements:

- A user must not infer metric existence for devices outside the account.
- A user must not preview label or maintenance outcome counts for unauthorized
  devices.
- Preview and create must use the same resolved account id.
- Returned blocking reasons must be sanitized. Do not reveal unauthorized ids in
  detail.
- Frontend restored drafts referencing inaccessible devices must be marked stale
  and blocked until corrected.

### Failure Modes

| Failure | User-visible behavior | Backend behavior |
| --- | --- | --- |
| Device service unavailable | Scope selector loads stale/empty state and preview is blocked. | Return dependency error, do not create dataset. |
| Telemetry service unavailable | Chart and preview show dependency error. | Return dependency error, do not create partial dataset. |
| Metric disappears after preview | Create returns stale preview or failed validation. | Revalidate and report changed metric coverage. |
| Maintenance outcome changes after preview | Failure label readiness becomes stale. | Recompute preview hash and require refresh. |
| Preview times out for large cohort | UI shows scoped error and suggests narrower range/cohort. | Enforce timeouts, return structured retry guidance. |
| Browser draft contains old ids | UI marks stale devices and blocks submit. | Backend still rejects unauthorized or missing ids. |
| User selects partial metric in common mode | UI disables or removes that metric. | Preview reports metric as blocked for common mode. |
| User creates without preview through legacy API | Dataset creation still validates fully. | Existing endpoint remains authoritative. |

## Frontend Architecture

### Component Structure

Target components under the existing frontend structure:

```text
frontend/src/pages/MlSettingsPage.tsx
frontend/src/analytics/mlModuleApi.ts
frontend/src/features/ml-datasets/
  components/DatasetWorkbench.tsx
  components/DeviceScopeSelector.tsx
  components/MetricCoverageMatrix.tsx
  components/TelemetryWindowChart.tsx
  components/CandidateWindowList.tsx
  components/DatasetReadinessPanel.tsx
  model/datasetWorkbench.types.ts
  model/datasetWorkbench.utils.ts
```

The page may initially keep the workbench inside `MlSettingsPage.tsx`, but the
target should move this workflow into feature components because the current
page is already large and combines many unrelated ML workflows.

### State Model

Workbench state:

- `modelFamily`
- `horizonDays`
- `deviceScope`
- `metricSelectionMode`
- `selectedMetrics`
- `focusedMetric`
- `range`
- `selectedCandidateId`
- `datasetDraft`
- `preview`
- `previewStatus`: `idle`, `loading`, `ready`, `stale`, `error`

Rules:

- Any change to scope, metrics, range, model family, horizon, or gates marks
  preview stale.
- Create is enabled only when preview status is `ready` and preview decision is
  not `BLOCKED`.
- Restored drafts must be reconciled against current device and metric
  availability before preview.

### Chart Behavior

The chart should use an existing proven chart implementation if available in
the repository. If no charting library exists, the first iteration may use the
existing lightweight telemetry SVG/canvas components, but interactions must be
tested with pointer events.

Required interactions:

- Hover shows timestamp, value, and quality status.
- Drag creates or updates selected range.
- Candidate bands can be clicked to select that window.
- Keyboard users can choose candidates from the candidate list and edit exact
  datetime fields.
- Mobile layout stacks scope, chart, readiness, and matrix without overlapping
  text.

### Empty States

Required empty states:

- No devices available.
- Device selected but no telemetry metrics.
- Metrics selected but no readings in range.
- Failure prediction selected but no maintenance/failure outcomes.
- Preview dependency unavailable.
- Preview stale after user changed inputs.

Empty states should say what is missing and which control can resolve it.

## API Contract Additions

The API docs should be updated when implementation starts. Until then, these are
target contracts, not live endpoints.

### Preview Request Types

```text
MlDatasetWorkbenchPreviewRequest
MlDatasetWorkbenchPreviewResponse
MlDatasetMetricCoverageDto
MlDatasetCandidateWindowDto
MlDatasetTimelineBucketDto
MlDatasetLabelReadinessDto
MlDatasetQualityGateDto
```

### Status Enums

```text
MlWorkbenchStatus = READY | READY_WITH_WARNINGS | BLOCKED
MlWorkbenchGateStatus = PASS | WARN | BLOCK
MlMetricSelectionMode = COMMON | ALL_AVAILABLE
MlCandidateWindowKind = BEST_COVERAGE | RECENT_STABLE | FAILURE_RICH | USER_SELECTED
```

## Observability

Analytics-service SHOULD expose metrics for preview and create behavior:

- preview request count by status;
- preview latency;
- preview dependency failures by service;
- candidate generation count;
- blocked preview count by reason;
- create after preview count;
- stale preview create attempts;
- dataset validation failure count by reason;
- feature snapshot/training continuation rate from created datasets.

Logs SHOULD include:

- account id;
- preview id or hash prefix;
- device count;
- metric count;
- model family;
- horizon days when applicable;
- status;
- blocking reason codes.

Logs MUST NOT include raw telemetry values.

## Security And Privacy

Requirements:

- Preview responses must be account scoped.
- Raw telemetry values returned for chart rendering must be limited and already
  authorized through telemetry APIs.
- Preview summaries must not leak unauthorized device ids, metric names, or
  maintenance outcome details.
- Drafts stored in browser local storage must not contain secrets.
- Dataset names and descriptions are user-controlled strings and must be
  escaped in the UI.
- Request sizes for device ids, selected metrics, and candidate limits must be
  bounded.

## Performance Targets

Initial local targets:

- Single device preview for 30 days: under 2 seconds.
- Up to 10 devices and 5 metrics for 30 days: under 5 seconds.
- Chart-focused metric request: under 2 seconds.
- Dataset create after preview: may take longer, but should return a clear
  progress/status response if materialization becomes asynchronous.

Production scaling expectations:

- Preview should use bucketed summaries, not raw per-point transfer, for large
  cohorts.
- Candidate generation should cap search windows and bucket granularity.
- The frontend should debounce preview requests while the user edits controls.
- A canceled or superseded preview request should not overwrite newer state in
  the UI.

## Compatibility With Existing ML Flow

The workbench feeds the existing lifecycle:

```text
Workbench preview
  -> internal telemetry dataset create
  -> dataset version detail and validation report
  -> horizon label generation for failure prediction
  -> feature snapshot generation
  -> training run
  -> model registry approval
  -> deployment policy
  -> live recalculation and monitoring
```

Existing archived OpenSpec specs already require:

- dataset selectors and metric selectors instead of raw ids;
- preserved form drafts;
- model-family-specific feature snapshot inputs;
- failure horizon consistency between labels, snapshots, and training.

The workbench is the production-grade interface and backend preview mechanism
that makes those requirements usable rather than merely available.

## Test Strategy

### Backend Unit And Service Tests

Required analytics-service coverage:

- Preview rejects empty device selection.
- Preview rejects inaccessible device ids without leaking unauthorized details.
- Preview returns only metrics present on selected devices.
- Common metrics mode returns intersection across selected devices.
- All-available mode returns union with coverage ratios.
- Preview blocks selected metrics with zero coverage.
- Candidate scoring prefers high coverage and low gaps.
- Candidate scoring produces `FAILURE_RICH` when labels are sufficient.
- Failure-prediction preview counts positive, normal, unresolved, excluded, and
  unobservable labels.
- Preview hash changes when scope, metrics, range, horizon, or gate thresholds
  change.
- Create with stale preview hash returns structured conflict.
- Legacy create without preview still validates and creates or fails correctly.

### Frontend Tests

Required UI coverage:

- Device search and cohort selection update available metrics.
- Metric list contains no default fallback metrics when selected devices expose
  none.
- Common/all-available toggle changes metric coverage matrix correctly.
- Candidate selection updates `from` and `to`.
- Brush selection updates `from` and `to`.
- Manual `from/to` edits mark preview stale and request a new preview.
- Blocked preview disables `Create dataset` and renders blocking reasons.
- Failure-prediction mode requires horizon and renders label readiness.
- Create request includes selected device ids, selected metric names, range,
  gates, and preview hash.
- Restored stale drafts are visible and cannot be submitted until corrected.

### Docker Verification

Extend `scripts/ml-failure-prediction-docker-smoke.ps1` or add a dedicated
workbench smoke mode that verifies:

1. create device and source token;
2. ingest synthetic telemetry with several metrics and intentional gaps;
3. create maintenance/failure outcomes;
4. call workbench preview;
5. assert available metrics match ingested metrics;
6. assert candidate windows include ready and blocked windows;
7. assert failure label readiness;
8. create dataset with preview hash;
9. generate horizon labels;
10. generate failure feature snapshot;
11. train model;
12. approve/deploy;
13. run live recalculation;
14. verify lifecycle cleanup paths still block or allow the expected records.

### Manual Browser Verification

Required checks:

- The workbench loads on `http://localhost:3000`.
- Device selector is usable with keyboard and mouse.
- Metric matrix does not overflow on desktop or mobile.
- Timeline candidate overlays are visible and do not hide axis labels.
- Brush interaction updates the selected range.
- Readiness panel remains visible or easily reachable during selection.
- Failure-prediction labels are visually distinct from candidate windows.
- Empty and dependency-error states are understandable.

## Rollout Plan

### Phase 1: Backend Preview Contract

- Add OpenSpec change for `build-ml-dataset-workbench`.
- Add preview DTOs and endpoint in analytics-service.
- Reuse or extract existing internal dataset validation logic.
- Add backend tests for metric discovery, coverage, gates, label readiness, and
  preview hash.
- Update `docs/api/ml-api.md`.

### Phase 2: Frontend Workbench

- Add frontend API client for preview.
- Replace the internal telemetry form with the workbench.
- Use real metric availability from preview.
- Add candidate window list, readiness panel, and metric matrix.
- Add a focused telemetry chart with candidate overlays and brush selection.
- Preserve drafts and stale entity handling.

### Phase 3: Feature Snapshot And Training Continuity

- Carry model family and horizon from workbench into feature snapshot defaults.
- Prioritize compatible feature snapshots in training selectors.
- Keep blocking behavior for family and horizon mismatches.
- Add tests covering the full UI path.

### Phase 4: Production Hardening

- Add telemetry summary optimization if preview latency is too high.
- Add observability metrics and logs.
- Extend Docker smoke for preview and lifecycle cleanup.
- Validate mobile/desktop layouts.
- Archive the OpenSpec change only after backend, frontend, docs, and Docker
  checks pass.

## Open Questions

- Should preview persist short-lived records, or is deterministic stateless hash
  enough for the first production implementation?
- Should telemetry-service expose a dedicated internal coverage summary
  endpoint, or should analytics-service aggregate from existing readings until
  cohort scale requires a new boundary?
- What default search range should candidate generation use when the user has
  not selected dates: 30 days, 90 days, or account/device-type dependent?
- Should `FAILURE_RICH` optimize for more positives, better class balance, or
  more recent failures?
- Should preventive maintenance ever be optionally positive for specific asset
  classes?
- What charting library, if any, should be introduced for robust brush
  interaction, accessibility, and tests?
