# Configure ML Approval Gates Design

## Gate Profile

An approval gate profile should include:

- profile id and version;
- applicable model family;
- optional account id, device type, model family, and horizon constraints;
- minimum positive labels;
- minimum label coverage;
- minimum precision;
- minimum recall;
- minimum PR-AUC or lift over baseline when available;
- maximum false alerts per device-month;
- minimum lead time when applicable;
- artifact load-test requirement;
- feature schema compatibility requirement.

Profiles can start as config-backed defaults and later move into database
metadata if operators need per-account editing.

## Training Runtime

ML-service should receive resolved gate thresholds or a profile reference in
the training request. It should write both observed values and thresholds into:

- training run response metrics;
- artifact manifest;
- `model.json`;
- `model-card.json`.

If thresholds are not provided, ML-service should use a named default profile
with non-trivial thresholds instead of hardcoded zero precision/recall gates.

## Backend Approval

Analytics-service should not approve a candidate model when required gate
metadata is missing or failed. The approval error should identify failed gates
and threshold source.

Backend approval remains the final operator action. ML-service gate metadata is
evidence; backend must enforce it before status moves to `APPROVED`.

## Frontend

Model cards should show:

- profile id/version;
- observed metric;
- threshold;
- pass/fail;
- operator-readable reason.

The Approve action should align with backend approval behavior. If backend
rejects, refresh the model and show the rejection reason.

## Tests

Tests should cover:

- configured thresholds are present in artifacts and model cards;
- failed precision/recall blocks promotion eligibility;
- backend approval rejects missing or failed required gates;
- frontend displays threshold values and backend rejection reasons.
