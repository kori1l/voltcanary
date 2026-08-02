## Why

VoltCanary currently produces anomaly and heuristic risk scores. That is not the
same as predicting equipment failure. Once immutable datasets, materialized
features, real artifacts, and outcome labels exist, the product needs a first
real supervised failure prediction model family.

The initial production model should be simple, explainable, and honestly
evaluated before advanced ML is considered.

## What Changes

- Add `FAILURE_PREDICTION` as a model family.
- Add horizon-specific failure risk fields to ML request/response and
  prediction traceability.
- Train supervised tabular models from outcome-derived labels.
- Use time-based splits and imbalanced-class metrics.
- Produce model cards, risk drivers, and approval gates.
- Keep anomaly/degradation outputs separate from failure risk.

## Capabilities

### New Capabilities

- `ml-failure-prediction`: supervised horizon-based equipment failure
  prediction, evaluation, model cards, and explainable risk output.

## Impact

- `ml-service/**`: supervised model adapters, training, evaluation, inference,
  risk drivers.
- `backend/services/analytics-service/**`: model family enum, training request,
  registry metadata, prediction traceability, fallback behavior.
- `frontend/src/**`: model creation templates, metrics comparison, risk driver
  display.
- `docs/api/**` and `docs/operations/**`: failure prediction contracts and
  promotion gates.

## Dependencies

Depends on:

- `close-ml-dataset-lifecycle`
- `materialize-ml-feature-platform`
- `unify-ml-artifacts-runtime`
- `failure-outcome-labeling`
