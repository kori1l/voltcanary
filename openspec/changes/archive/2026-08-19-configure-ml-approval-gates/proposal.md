# Configure ML Approval Gates

## Why

Failure-prediction training already produces approval-gate metadata, but some
thresholds are hardcoded in Python with permissive defaults. A production model
cannot be approved because precision and recall are merely non-negative; every
classifier satisfies those thresholds.

Approval gates need explicit, operator-visible profiles that can vary by model
family, device type, account, and rollout stage.

## What Changes

- Introduce configurable ML approval gate profiles.
- Apply configured thresholds during training and backend approval.
- Include threshold source and profile id/version in metrics, model artifacts,
  model cards, and registry details.
- Block approval or deployment when required gates fail.
- Update the frontend model card to show configured thresholds and pass/fail
  reasons.

## Impact

Affected specs:

- `ml-failure-prediction`

Affected implementation areas:

- `ml-service/app/services/training_service.py`
- `ml-service/app/models/failure_evaluation.py`
- backend analytics model registry approval logic
- model version DTOs and frontend model cards
- `docs/api/ml-api.md`
- `docs/operations/ml-operations.md`

Compatibility:

- Existing model cards remain readable.
- New trained models include approval profile metadata and stricter promotion
  eligibility.
