# Design

## Overview

Implement `ML Dataset Workbench` as the production workflow for creating
internal telemetry ML dataset versions. The workbench follows the target
architecture in `docs/architecture/11-ml-dataset-workbench.md`.

Core principle:

```text
algorithm recommends -> user inspects or overrides -> backend previews gates
-> user commits immutable dataset
```

The frontend remains an operational console. It visualizes backend preview
results and user choices, but it does not decide independently that a dataset is
valid.

## Backend Preview Boundary

Add a read-only endpoint owned by analytics-service:

```text
POST /analytics/ml/datasets/internal-telemetry/preview
```

Responsibilities:

- authorize selected devices using the same account boundary as dataset create;
- discover available metrics for selected devices;
- compute metric coverage for common and partial metric modes;
- summarize telemetry density, gaps, duplicates, non-finite values, and unit
  warnings;
- recommend candidate time windows;
- evaluate quality gates;
- evaluate failure-prediction label readiness when `modelFamily` is
  `FAILURE_PREDICTION`;
- return a deterministic preview hash and create defaults.

The endpoint must not mutate ML lifecycle state. It may use existing telemetry
chart/readings APIs for the first implementation, but the response contract
should be compatible with a later telemetry coverage-summary adapter.

## Create Compatibility

Extend the internal telemetry create request with optional `previewId` and
`previewHash`.

Rules:

- Existing callers without preview fields remain valid.
- Workbench callers send preview fields when available.
- Create always reruns validation and materialization.
- If supplied preview fields no longer match recomputed validation, return a
  structured stale-preview conflict instead of silently creating a different
  dataset.
- Final validation reports store the selected preview summary in
  `qualityProfile`.

## Candidate Windows

Return ranked candidate windows:

- `BEST_COVERAGE`
- `RECENT_STABLE`
- `FAILURE_RICH`
- `USER_SELECTED`

Candidate scoring uses coverage, density, max gap, quality flags, freshness,
maintenance exclusions, failure-label coverage, class balance, and leakage risk.
Exact weights are implementation details, but each candidate must include a
short explanation and warning/blocking reasons.

## Failure Prediction

Failure preview evaluates label readiness for a horizon without generating or
replacing labels.

The preview distinguishes:

- positive labels;
- normal labels;
- unresolved labels;
- unobservable rows near the right edge of the data window;
- maintenance-excluded rows.

Actual horizon-label generation remains a separate lineage-changing action.

## Frontend Boundary

Frontend delivery is intentionally scoped to companion change
`build-ml-dataset-workbench-frontend`. This platform change defines the preview
and create contracts that the frontend consumes.

## Testing

Add backend service tests for preview authorization, metric coverage, candidate
selection, label readiness, preview hash, stale create handling, and legacy
create compatibility.

Extend Docker smoke coverage to call preview before dataset create and assert
that preview, create, feature snapshot, training, deployment, live recalculation,
and lifecycle cleanup still work together.

## Risks

- Preview can become expensive for large cohorts if it fetches raw readings for
  every device/metric pair.
- Frontend chart interactions can become inaccessible if only pointer-driven
  selection is implemented.
- Preview and create can drift if they use separate validation paths.
- Label readiness can mislead users if it mutates labels or hides
  unobservable/unresolved rows.

Mitigations:

- Keep preview response bucketed and capped.
- Reuse or extract dataset validation logic instead of duplicating it.
- Provide candidate list and exact datetime inputs alongside chart brush.
- Keep label preview read-only and require explicit label generation.
