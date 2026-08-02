## Context

Failure prediction should be formulated as a horizon-based classification
problem for the first production version:

> Given information available at `predictionAt`, estimate risk of confirmed
> failure or corrective maintenance in the next N days.

This avoids vague claims like "the device will fail soon" and gives evaluation
clear boundaries.

## Goals

- Add a production-usable `FAILURE_PREDICTION` model family.
- Train a simple supervised model from materialized features and outcome labels.
- Report meaningful time-based evaluation metrics.
- Return explainable risk drivers and confidence.
- Keep fallback and compatibility with existing prediction fields.

## Non-Goals

- Do not introduce deep learning in the first supervised slice.
- Do not promise exact diagnosis of a specific component failure.
- Do not treat unlabeled anomalies as confirmed failures.

## Design

### Target

The first model target is:

- positive: confirmed failure or corrective maintenance within configured
  horizon;
- negative: enough future observation exists and no positive outcome occurs in
  the horizon;
- excluded: maintenance windows, unresolved anomalies, sensor faults, or
  insufficient future observation depending on config.

Initial horizons:

- 7 days;
- 14 days;
- 30 days.

### Algorithms

Initial supervised algorithms:

- logistic regression baseline;
- random forest;
- histogram gradient boosting.

Anomaly detection remains useful as a separate signal, but not as a substitute
for failure probability.

### Evaluation

Use time-based splits by default. Metrics:

- precision, recall, F1 per horizon;
- PR-AUC;
- false alerts per device per month;
- recall at fixed false-alert budget;
- average lead time before outcome;
- calibration error when output is probability-like;
- coverage and fallback conditions.

### Output Contract

Additive ML response fields:

- `modelVersionId`
- `modelFamily`
- `failureRiskScore`
- `failureHorizonDays`
- `failureRiskBand`
- `riskDrivers`
- `labelCoverage`
- `trainingDataWindow`

Existing fields such as `anomalyScore`, `degradationScore`, `riskLevel`,
`confidence`, `signals`, and `explanations` remain backward-compatible.

### Approval Gates

Model approval should require configured minimums, for example:

- minimum positive outcomes;
- minimum label coverage;
- minimum precision or recall threshold;
- maximum false-alert rate;
- successful artifact load test;
- compatible feature schema.

## Risks

- Failure labels may be sparse. The model must report insufficient label
  coverage instead of pretending to be reliable.
- Time leakage is easy in maintenance data. Feature windows must use only data
  available at `predictionAt`.
- Users may interpret risk as diagnosis. UI copy should stay probabilistic and
  evidence-based.
