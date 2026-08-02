from datetime import datetime, timedelta, timezone

from app.schemas.training import TrainingRequest


FAILURE_FIXTURE_ACCOUNT_ID = "account-1"
FAILURE_FIXTURE_DATASET_VERSION_ID = "dataset-failure-known-outcomes-v1"
FAILURE_FIXTURE_FEATURE_SCHEMA_HASH = "sha256:failure-fixture"
FAILURE_FIXTURE_HORIZON_DAYS = 14
FAILURE_FIXTURE_START = datetime(2026, 7, 1, tzinfo=timezone.utc)

EXPECTED_FAILURE_FIXTURE_SUMMARY = {
    "failureTargetSamples": 6.0,
    "failureUsableSamples": 6.0,
    "failurePositiveLabels": 3.0,
    "failureNegativeLabels": 3.0,
    "failureExcludedSamples": 0.0,
    "failurePositiveRate": 0.5,
    "failureClassImbalanceRatio": 1.0,
    "labelCoverage": 1.0,
}

_OUTCOMES = [
    (True, "CONFIRMED_FAILURE", 120.0, 1.8),
    (False, "NORMAL", 90.0, 0.9),
    (True, "CORRECTIVE_MAINTENANCE", 125.0, 1.9),
    (False, "INSPECTION_NORMAL", 88.0, 0.8),
    (True, "CONFIRMED_FAILURE", 130.0, 2.0),
    (False, "FALSE_POSITIVE", 86.0, 0.7),
]


def known_failure_outcome_rows() -> list[dict]:
    rows: list[dict] = []
    for index, (failure, outcome, rolling_mean, deviation) in enumerate(_OUTCOMES):
        timestamp = FAILURE_FIXTURE_START + timedelta(days=index)
        feature_row_id = f"known-outcome-row-{index}"
        metadata = {
            "featureRowId": feature_row_id,
            "failureWithinHorizon": failure,
            "failureOutcomeType": outcome,
            "horizonDays": FAILURE_FIXTURE_HORIZON_DAYS,
            "featureSchemaHash": FAILURE_FIXTURE_FEATURE_SCHEMA_HASH,
        }
        if failure:
            metadata["leadTimeDays"] = 5.0
        rows.extend(
            [
                _row(timestamp, feature_row_id, "rollingMean", rolling_mean, metadata),
                _row(timestamp, feature_row_id, "baselineDeviationRatio", deviation, metadata),
            ]
        )
    return rows


def known_failure_training_request(
    *,
    dataset_version_id: str = FAILURE_FIXTURE_DATASET_VERSION_ID,
    minimum_positive_labels: int | None = None,
    minimum_label_coverage: float | None = None,
) -> TrainingRequest:
    body = {
        "datasetVersionId": dataset_version_id,
        "accountId": FAILURE_FIXTURE_ACCOUNT_ID,
        "sourceType": "INTERNAL_TELEMETRY",
        "modelFamily": "FAILURE_PREDICTION",
        "failureHorizonDays": FAILURE_FIXTURE_HORIZON_DAYS,
        "rows": known_failure_outcome_rows(),
    }
    if minimum_positive_labels is not None:
        body["minimumPositiveLabels"] = minimum_positive_labels
    if minimum_label_coverage is not None:
        body["minimumLabelCoverage"] = minimum_label_coverage
    return TrainingRequest.model_validate(body)


def _row(timestamp: datetime, feature_row_id: str, metric: str, value: float, metadata: dict) -> dict:
    return {
        "timestamp": timestamp.isoformat(),
        "deviceId": "device-1",
        "metric": metric,
        "value": value,
        "metadata": dict(metadata, sourceFixture="known-failure-outcomes-v1", featureRowId=feature_row_id),
    }
