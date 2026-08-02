## 1. Contracts

- [x] 1.1 Add `FAILURE_PREDICTION` model family.
- [x] 1.2 Add horizon and failure-risk fields to ML response additively.
- [x] 1.3 Add failure-prediction training request parameters.
- [x] 1.4 Extend prediction traceability for failure risk and risk drivers.
- [x] 1.5 Update ML API docs.

## 2. Training

- [x] 2.1 Implement horizon target construction from generated labels.
- [x] 2.2 Implement logistic regression baseline.
- [x] 2.3 Implement random forest or histogram gradient boosting model.
- [x] 2.4 Handle class imbalance and insufficient positive labels.
- [x] 2.5 Serialize model and preprocessing artifacts.

## 3. Evaluation

- [x] 3.1 Implement time-based split for failure prediction.
- [x] 3.2 Compute precision, recall, F1, PR-AUC, false-alert rate, and lead time.
- [x] 3.3 Compare candidate model against baseline heuristic.
- [x] 3.4 Generate model card artifact.
- [x] 3.5 Add approval gate metadata.

## 4. Inference

- [x] 4.1 Load failure-prediction models by `modelVersionId`.
- [x] 4.2 Generate failure risk, horizon, confidence, and risk drivers.
- [x] 4.3 Keep anomaly/degradation outputs separate from failure risk.
- [x] 4.4 Return explicit insufficient-label or insufficient-feature statuses.

## 5. Frontend

- [x] 5.1 Add failure-prediction training template.
- [x] 5.2 Show horizon-specific metrics and model cards.
- [x] 5.3 Show failure risk and risk drivers in model/prediction detail.
- [x] 5.4 Block or warn on promotion when gates fail.

## 6. Verification

- [x] 6.1 Add deterministic fixture datasets with known outcomes.
- [x] 6.2 Add ML-service supervised training/evaluation tests.
- [x] 6.3 Add analytics-service contract and traceability tests.
- [x] 6.4 Add frontend tests for failure model workflows.
- [x] 6.5 Run `npm run openspec:validate`.
